<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { chatAi, clearAiHistory, fetchAiConfig, fetchAiHistory } from '@/api/ai'
import type { AiChatEvent, AiChatMessage, AiConfig, AiHistoryMessage, AiPick } from '@/api/ai'
import { tryIwencaiV } from '@/utils/iwencaiV'
import { fmtNum, fmtSigned, toneClass } from '@/utils/stockFormat'

/**
 * 详情页的 AI 对话抽屉。
 *
 * <p>做成右侧抽屉而不是独立路由：用户看盘时是「边看边问」的，
 * 跳走再回来会丢当前 K 线/周期状态。所以用 Teleport 挂在 body 上，
 * z-index 压在既有弹窗（`.stock-modal` 为 100）之上，避免被详情页弹窗盖住。</p>
 *
 * <p><b>同一个组件也承担全局 AI 入口</b>（导航栏按钮 / Ctrl+N）：这时不绑定个股，
 * {@code code} 与 {@code name} 留空，后端 {@code AiContextBuilder} 会切成「全局对话」分支，
 * 不预加载任何个股行情。刻意复用而不是另写一个对话框——两套对话 UI 会分叉出两套
 * 流式解析、两套工具状态渲染，维护成本翻倍。</p>
 */

interface Props {
  open: boolean
  /** 当前个股代码；全局入口传空串 */
  code: string
  /** 当前个股名称；全局入口传空串 */
  name: string
  /**
   * 预置输入内容（不自动发送）。
   *
   * <p>问财选股页把表格结果拼成文本塞进来，用户能在输入框里先看到、再补自己的问题。
   * 不自动发送是刻意的：预置内容来自另一个页面，直接发出去用户看不到它到底问了什么。</p>
   */
  preset?: string
}

const props = withDefaults(defineProps<Props>(), { preset: '' })
const emit = defineEmits<{ close: []; presetConsumed: [] }>()

interface ToolTrace {
  id: number
  name: string
  label: string
  status: 'running' | 'done'
}

interface ChatMessage {
  id: number
  role: 'user' | 'assistant'
  content: string
  reasoning: string
  tools: ToolTrace[]
  picks: AiPick[]
  streaming: boolean
}

const SUGGESTIONS = ['帮我分析这只票', '最近有什么消息', '主力资金怎么样', '帮我选股']

/** 后端工具名 → 中文状态条；未知工具直接显示原名，别把英文内部标识糊到用户脸上 */
const TOOL_LABELS: Record<string, string> = {
  get_stock_quote: '获取行情',
  get_stock_quotes_batch: '批量获取行情',
  get_stock_kline: '获取 K 线',
  get_stock_money_flow: '获取资金流向',
  get_stock_money_flow_history: '获取资金流历史',
  get_stock_industry: '获取行业信息',
  get_market_indices: '获取大盘指数',
  get_stock_intraday: '获取分时数据',
  get_hot_list: '获取热榜',
  search_stocks: '搜索股票',
  read_user_profile: '读取用户偏好',
  save_user_profile: '保存用户偏好',
  get_fx_rate: '获取汇率',
  web_search: '联网搜索',
  web_fetch: '抓取网页',
  stock_screener: '筛选股票',
  render_stock_picks: '生成选股卡片'
}

const router = useRouter()

const config = ref<AiConfig | null>(null)
const configLoading = ref(false)
const configError = ref('')
const messages = ref<ChatMessage[]>([])
const input = ref('')
const errorMsg = ref('')
const streaming = ref(false)
const historyLoading = ref(false)
const clearing = ref(false)
const bodyRef = ref<HTMLElement | null>(null)
const inputRef = ref<HTMLTextAreaElement | null>(null)

let controller: { abort: () => void } | null = null
let messageSeq = 0
let configSeq = 0
/** 历史请求序号：切股票时作废旧请求，避免慢响应把新股票的历史盖回来 */
let historySeq = 0
/** 用户是否还贴着底部；上滑超过 80px 就不再自动滚，免得抢滚动 */
let stickToBottom = true
let scrollFrame = 0

const unavailable = computed(() => config.value !== null && !config.value.available)
const canSend = computed(() => !unavailable.value && !streaming.value && input.value.trim().length > 0)
/** 全局入口：没有绑定个股，文案与快捷问句都要跟着换，不能再出现「这只票」 */
const isGlobal = computed(() => !props.code)
const suggestions = computed(() =>
  isGlobal.value
    ? ['今天大盘怎么样', '最近哪些板块强势', '帮我按条件选股', '给我讲讲可转债']
    : SUGGESTIONS
)
const lastAssistant = computed<ChatMessage | null>(() => {
  const message = messages.value[messages.value.length - 1]
  return message && message.role === 'assistant' ? message : null
})

watch(
  () => props.open,
  (open) => {
    if (!open) {
      return
    }
    if (!config.value && !configLoading.value) {
      void loadConfig()
    }
    // 预置内容只在打开时灌一次，用户改过之后不该被重新覆盖
    if (props.preset) {
      input.value = props.preset
      emit('presetConsumed')
    }
    // 关闭再打开时本地对话还在，不必回库；只有首次打开或刷新后才拉历史
    if (!messages.value.length) {
      void loadHistory()
    }
    stickToBottom = true
    scrollSoon(true)
  },
  { immediate: true }
)

// 换股票等于换上下文：旧对话留着会误导模型，也会串到新股票上
watch(
  () => props.code,
  () => {
    controller?.abort()
    controller = null
    streaming.value = false
    // 旧股票的历史请求晚到也不能再写进 messages
    historySeq++
    historyLoading.value = false
    messages.value = []
    input.value = ''
    errorMsg.value = ''
    stickToBottom = true
    if (props.open) {
      void loadHistory()
    }
  }
)

watch(input, () => {
  void nextTick(autoGrow)
})

onMounted(() => {
  window.addEventListener('keydown', onWindowKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onWindowKeydown)
  controller?.abort()
  controller = null
  if (scrollFrame) {
    cancelAnimationFrame(scrollFrame)
    scrollFrame = 0
  }
})

/** 抽屉是最上层，Esc 关它符合习惯；只在打开时响应，避免抢详情页其它弹窗的 Esc */
function onWindowKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && props.open) {
    close()
  }
}

async function loadConfig() {
  const seq = ++configSeq
  configLoading.value = true
  try {
    const data = await fetchAiConfig()
    if (seq !== configSeq) {
      return
    }
    config.value = data
    configError.value = ''
  } catch (err) {
    if (seq !== configSeq) {
      return
    }
    configError.value = errText(err)
  } finally {
    if (seq === configSeq) {
      configLoading.value = false
    }
  }
}

/**
 * 拉取当前股票（空代码=全局助手）的历史对话。
 *
 * <p>只在本地还没有消息时填充：用户刚聊到一半切走再回来时，本地状态比库里更完整
 * （本轮回答可能还没落库），不能被旧历史盖掉。</p>
 */
async function loadHistory() {
  if (messages.value.length) {
    return
  }
  const codeAtLoad = props.code
  const seq = ++historySeq
  historyLoading.value = true
  try {
    const data = await fetchAiHistory(codeAtLoad)
    if (seq !== historySeq || codeAtLoad !== props.code || messages.value.length) {
      return
    }
    messages.value = (data.messages ?? []).map(toChatMessage)
    scrollSoon(true)
  } catch {
    // 历史读不到不影响新对话：用户仍可继续提问，只是看不到旧记录
  } finally {
    if (seq === historySeq) {
      historyLoading.value = false
    }
  }
}

function toChatMessage(message: AiHistoryMessage): ChatMessage {
  return {
    id: ++messageSeq,
    role: message.role,
    content: message.content || '',
    reasoning: message.reasoning || '',
    tools: (message.tools ?? []).map((tool, index) => ({
      id: index + 1,
      name: tool.name,
      label: toolLabel(tool.name, tool.args),
      status: 'done' as const
    })),
    picks: message.picks ?? [],
    streaming: false
  }
}

async function clearHistory() {
  if (streaming.value || clearing.value || !messages.value.length) {
    return
  }
  clearing.value = true
  try {
    await clearAiHistory(props.code)
    messages.value = []
    errorMsg.value = ''
    stickToBottom = true
  } catch (err) {
    errorMsg.value = err instanceof Error && err.message ? err.message : '清空历史失败'
  } finally {
    clearing.value = false
  }
}

async function send(prompt?: string) {
  const text = (prompt ?? input.value).trim()
  if (!text || streaming.value || unavailable.value) {
    return
  }
  input.value = ''
  errorMsg.value = ''
  const codeAtSend = props.code
  const userMessage: ChatMessage = {
    id: ++messageSeq,
    role: 'user',
    content: text,
    reasoning: '',
    tools: [],
    picks: [],
    streaming: false
  }
  const assistantMessage: ChatMessage = {
    id: ++messageSeq,
    role: 'assistant',
    content: '',
    reasoning: '',
    tools: [],
    picks: [],
    streaming: true
  }
  messages.value.push(userMessage, assistantMessage)
  streaming.value = true
  scrollSoon(true)
  // 问财凭证要先在浏览器里算（服务端算不出来），拿不到就带空串继续，不影响普通问答
  const iwencaiV = await tryIwencaiV()
  if (codeAtSend !== props.code || !streaming.value) {
    // 等凭证这段时间用户换了股票、或点了停止/关闭，这次请求已经过期，直接丢弃
    return
  }
  controller = chatAi(
    { messages: buildHistory(), stockCode: props.code, stockName: props.name, iwencaiV },
    { onEvent: handleEvent, onError: handleError, onClose: handleClose }
  )
}

/** 只回传已经定稿的消息；正在流式输出的那条还没说完，回传等于让模型复读自己 */
function buildHistory(): AiChatMessage[] {
  const history: AiChatMessage[] = []
  for (const message of messages.value) {
    if (message.role === 'assistant' && message.streaming) {
      continue
    }
    const content = message.content.trim()
    if (!content) {
      continue
    }
    history.push({ role: message.role, content })
  }
  // 上下文越长首字越慢，近几轮通常就够了
  return history.slice(-20)
}

function handleEvent(event: AiChatEvent) {
  const message = lastAssistant.value
  if (!message) {
    return
  }
  switch (event.type) {
    case 'reasoning':
      if (event.text) {
        message.reasoning += event.text
      }
      break
    case 'content':
      if (event.text) {
        message.content += event.text
      }
      break
    case 'tool_call':
      pushTool(event.name || 'tool', event.args)
      break
    case 'tool_result':
      settleTool(event.name)
      break
    case 'done':
      if (event.picks && event.picks.length) {
        message.picks = event.picks
      }
      message.streaming = false
      streaming.value = false
      controller = null
      break
    case 'error':
      message.streaming = false
      streaming.value = false
      controller = null
      errorMsg.value = event.message || 'AI 分析失败'
      break
  }
  scrollSoon()
}

function handleError(message: string) {
  errorMsg.value = message
  streaming.value = false
  controller = null
  const assistant = lastAssistant.value
  if (assistant) {
    assistant.streaming = false
  }
}

function handleClose() {
  streaming.value = false
  controller = null
  const message = lastAssistant.value
  if (!message) {
    return
  }
  message.streaming = false
  const empty = !message.content.trim() && !message.reasoning.trim() && !message.tools.length && !message.picks.length
  if (empty) {
    message.content = '（本次没有返回内容）'
  }
}

function pushTool(name: string, args: unknown) {
  const message = lastAssistant.value
  if (!message) {
    return
  }
  message.tools.push({
    id: message.tools.length + 1,
    name,
    label: toolLabel(name, args),
    status: 'running'
  })
}

/** 工具参数里一般带 code，拼到状态条上，用户才知道这条在查哪只股票 */
function toolLabel(name: string, args: unknown): string {
  const label = TOOL_LABELS[name] ?? name
  const code = readArgCode(args)
  return code ? `${label}（${code}）` : label
}

/** args 可能是对象，也可能是一段 JSON 字符串（后端透传模型给的 arguments） */
function readArgCode(args: unknown): string {
  if (!args) {
    return ''
  }
  if (typeof args === 'string') {
    try {
      return readArgCode(JSON.parse(args) as unknown)
    } catch {
      return ''
    }
  }
  if (typeof args === 'object') {
    const raw = args as Record<string, unknown>
    const value = raw.code ?? raw.stockCode ?? raw.codes
    if (typeof value === 'string') {
      return value
    }
    if (Array.isArray(value) && value.length) {
      return String(value[0])
    }
  }
  return ''
}

/** 同名工具优先配对；没有名字就结算最后一个还在跑的工具 */
function settleTool(name?: string) {
  const message = lastAssistant.value
  if (!message) {
    return
  }
  const reversed = [...message.tools].reverse()
  const target = name
    ? reversed.find((tool) => tool.name === name && tool.status === 'running')
    : reversed.find((tool) => tool.status === 'running')
  if (target) {
    target.status = 'done'
  }
}

function stop() {
  controller?.abort()
  controller = null
  streaming.value = false
  const message = lastAssistant.value
  if (message) {
    message.streaming = false
    if (!message.content.trim() && !message.reasoning.trim()) {
      message.content = '（已停止生成）'
    }
  }
}

function close() {
  controller?.abort()
  controller = null
  streaming.value = false
  emit('close')
}

function openPick(code: string) {
  close()
  void router.push(`/stock/${encodeURIComponent(code)}`)
}

function pickSuggestion(text: string) {
  send(text)
}

function onInputKeydown(event: KeyboardEvent) {
  // 输入法确认候选词的 Enter 不能当发送
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) {
    return
  }
  event.preventDefault()
  send()
}

function autoGrow() {
  const el = inputRef.value
  if (!el) {
    return
  }
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 140)}px`
}

function onBodyScroll() {
  const el = bodyRef.value
  if (!el) {
    return
  }
  stickToBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80
}

function scrollSoon(force = false) {
  if (force) {
    stickToBottom = true
  }
  if (!stickToBottom || scrollFrame) {
    return
  }
  // 流式内容每个 token 都会触发，用 rAF 合并成每帧一次
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = 0
    const el = bodyRef.value
    if (el && stickToBottom) {
      el.scrollTop = el.scrollHeight
    }
  })
}

type ContentBlock =
  | { type: 'text'; text: string }
  | { type: 'code'; code: string }
  | { type: 'table'; rows: string[][] }

/**
 * 极简内容解析：不引 markdown 库，只把代码块和表格摘出来单独渲染，
 * 其余按纯文本 + 换行展示。AI 偶尔返回 `|---|` 这种表格，
 * 直接原样塞进气泡会挤成一团，至少在窄抽屉里要能横向滚动。
 */
function parseContent(content: string): ContentBlock[] {
  const blocks: ContentBlock[] = []
  const lines = content.replace(/\r\n/g, '\n').split('\n')
  let textLines: string[] = []
  let codeLines: string[] | null = null
  let tableLines: string[] = []

  const flushText = () => {
    const text = textLines.join('\n').trimEnd()
    if (text.trim()) {
      blocks.push({ type: 'text', text })
    }
    textLines = []
  }
  const flushCode = () => {
    if (codeLines) {
      blocks.push({ type: 'code', code: codeLines.join('\n') })
      codeLines = null
    }
  }
  const flushTable = () => {
    if (!tableLines.length) {
      return
    }
    const rows = tableLines
      .filter((line) => !isTableDivider(line))
      .map(splitTableRow)
      .filter((row) => row.length > 1)
    if (rows.length) {
      blocks.push({ type: 'table', rows })
    }
    tableLines = []
  }

  for (const line of lines) {
    const trimmed = line.trim()
    if (codeLines !== null) {
      if (trimmed.startsWith('```')) {
        flushCode()
      } else {
        codeLines.push(line)
      }
      continue
    }
    if (trimmed.startsWith('```')) {
      flushText()
      flushTable()
      codeLines = []
      continue
    }
    if (isTableLine(trimmed)) {
      flushText()
      tableLines.push(trimmed)
      continue
    }
    flushTable()
    textLines.push(line)
  }
  flushCode()
  flushTable()
  flushText()
  return blocks
}

function isTableLine(line: string): boolean {
  return line.startsWith('|') && line.endsWith('|') && line.length >= 3
}

function isTableDivider(line: string): boolean {
  return /^\|?[\s:|-]+\|?$/.test(line) && line.includes('-')
}

function splitTableRow(line: string): string[] {
  const body = line.replace(/^\|/, '').replace(/\|$/, '')
  return body.split('|').map((cell) => cell.trim())
}

function errText(err: unknown): string {
  return err instanceof Error && err.message ? err.message : 'AI 配置读取失败'
}
</script>

<template>
  <Teleport to="body">
    <Transition name="ai-slide">
      <div v-if="open" class="ai-mask" @click.self="close">
        <aside class="ai-panel" role="dialog" aria-modal="true" :aria-label="isGlobal ? 'AI 助手' : 'AI 分析'">
          <header class="ai-panel__head">
            <div class="ai-panel__titles">
              <h2 class="ai-panel__title">{{ isGlobal ? 'AI 助手' : 'AI 分析' }}</h2>
              <div class="ai-panel__chips">
                <span v-if="config" class="ai-chip">{{ config.model }}</span>
                <span v-if="config?.webSearchEnabled" class="ai-chip ai-chip--on">联网搜索</span>
                <span v-if="config?.thinkingEnabled" class="ai-chip ai-chip--think">深度思考</span>
              </div>
            </div>
            <div class="ai-panel__actions">
              <button
                v-if="messages.length"
                type="button"
                class="ai-clear"
                :disabled="streaming || clearing"
                @click="clearHistory"
              >
                清空
              </button>
              <button type="button" class="ai-panel__close" aria-label="关闭 AI 面板" @click="close">×</button>
            </div>
          </header>

          <div ref="bodyRef" class="ai-panel__body" @scroll.passive="onBodyScroll">
            <div v-if="configLoading && !config" class="ai-state">正在读取 AI 配置…</div>

            <div v-else-if="unavailable" class="ai-empty">
              <p class="ai-empty__title">AI 未配置</p>
              <p class="ai-empty__desc">请到管理后台 → 行情配置 填写 DeepSeek API Key，保存后刷新本页。</p>
            </div>

            <div v-else-if="historyLoading && !messages.length" class="ai-state">正在读取历史对话…</div>

            <div v-else-if="!messages.length" class="ai-empty">
              <p class="ai-empty__title">
                {{ isGlobal ? '问点什么？' : `和 AI 聊聊 ${name || '这只股票'}` }}
              </p>
              <p class="ai-empty__desc">
                {{
                  isGlobal
                    ? '可以问大盘、板块、个股、公告，也可以让它按条件帮你选股。'
                    : '可以问行情、资金、消息面，也可以让它按条件帮你选股。'
                }}
              </p>
              <div class="ai-empty__ask">
                <button
                  v-for="item in suggestions"
                  :key="item"
                  type="button"
                  class="ai-ask"
                  @click="pickSuggestion(item)"
                >
                  {{ item }}
                </button>
              </div>
            </div>

            <template v-for="message in messages" :key="message.id">
              <div v-if="message.role === 'user'" class="ai-msg ai-msg--user">
                <div class="ai-bubble ai-bubble--user">{{ message.content }}</div>
              </div>

              <div v-else class="ai-msg ai-msg--assistant">
                <details v-if="message.reasoning" class="ai-think">
                  <summary class="ai-think__summary">思考过程（{{ message.reasoning.length }} 字）</summary>
                  <p class="ai-think__body">{{ message.reasoning }}</p>
                </details>

                <ul v-if="message.tools.length" class="ai-tools">
                  <li
                    v-for="tool in message.tools"
                    :key="tool.id"
                    class="ai-tool"
                    :class="{ 'is-running': tool.status === 'running' }"
                  >
                    <span class="ai-tool__icon">{{ tool.status === 'running' ? '🔧' : '✓' }}</span>
                    <span>{{ tool.label }}<template v-if="tool.status === 'running'">…</template></span>
                  </li>
                </ul>

                <div v-if="message.content" class="ai-bubble ai-bubble--assistant">
                  <template v-for="(block, index) in parseContent(message.content)" :key="index">
                    <pre v-if="block.type === 'code'" class="ai-code">{{ block.code }}</pre>
                    <div v-else-if="block.type === 'table'" class="ai-table-wrap">
                      <table class="ai-table">
                        <tbody>
                          <tr v-for="(row, rowIndex) in block.rows" :key="rowIndex">
                            <td v-for="(cell, cellIndex) in row" :key="cellIndex">{{ cell }}</td>
                          </tr>
                        </tbody>
                      </table>
                    </div>
                    <p v-else class="ai-text">{{ block.text }}</p>
                  </template>
                  <span v-if="message.streaming" class="ai-caret" aria-hidden="true"></span>
                </div>
                <p v-else-if="message.streaming" class="ai-bubble ai-bubble--pending">正在思考…</p>

                <div v-if="message.picks.length" class="ai-picks">
                  <article v-for="pick in message.picks" :key="pick.code" class="ai-pick">
                    <div class="ai-pick__head">
                      <span class="ai-pick__name">{{ pick.name }}</span>
                      <span class="ai-pick__code">{{ pick.code }}</span>
                    </div>
                    <div class="ai-pick__quote">
                      <span class="ai-pick__price">{{ pick.price == null ? '--' : fmtNum(pick.price) }}</span>
                      <span :class="toneClass(pick.changePct)">
                        {{ pick.changePct == null ? '--' : fmtSigned(pick.changePct) + '%' }}
                      </span>
                    </div>
                    <p v-if="pick.reason" class="ai-pick__reason">{{ pick.reason }}</p>
                    <button type="button" class="btn btn--ghost ai-pick__btn" @click="openPick(pick.code)">查看</button>
                  </article>
                </div>
              </div>
            </template>
          </div>

          <div v-if="errorMsg" class="ai-alert" role="alert">
            <span class="ai-alert__text">{{ errorMsg }}</span>
            <button type="button" class="ai-alert__close" aria-label="关闭错误提示" @click="errorMsg = ''">×</button>
          </div>
          <div v-if="configError && !unavailable" class="ai-alert" role="alert">
            <span class="ai-alert__text">{{ configError }}</span>
            <button type="button" class="ai-alert__retry" @click="loadConfig">重试</button>
          </div>

          <footer class="ai-panel__foot">
            <div v-if="messages.length" class="ai-quick">
              <button
                v-for="item in suggestions"
                :key="item"
                type="button"
                class="ai-quick__chip"
                :disabled="streaming || unavailable"
                @click="pickSuggestion(item)"
              >
                {{ item }}
              </button>
            </div>
            <div class="ai-input">
              <textarea
                ref="inputRef"
                v-model="input"
                class="ai-input__box"
                rows="2"
                aria-label="向 AI 提问"
                :disabled="unavailable"
                :placeholder="unavailable ? 'AI 未配置，暂不可用' : '问点什么…（Enter 发送，Shift+Enter 换行）'"
                @keydown="onInputKeydown"
              ></textarea>
              <button v-if="!streaming" type="button" class="btn ai-input__send" :disabled="!canSend" @click="send()">
                发送
              </button>
              <button v-else type="button" class="btn btn--ghost ai-input__send" @click="stop">停止</button>
            </div>
            <p class="ai-footnote">内容由 AI 生成，仅供参考，不构成投资建议。</p>
          </footer>
        </aside>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.ai-mask {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--s-5);
  background: rgba(15, 20, 22, 0.32);
  backdrop-filter: blur(2px);
}

.ai-panel {
  display: flex;
  flex-direction: column;
  width: 80%;
  max-width: 100%;
  height: min(760px, 100%);
  overflow: hidden;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: 16px;
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.28);
}

.ai-slide-enter-active,
.ai-slide-leave-active {
  transition: opacity 0.18s var(--ease);
}

.ai-slide-enter-active .ai-panel,
.ai-slide-leave-active .ai-panel {
  transition: transform 0.22s var(--ease);
}

.ai-slide-enter-from,
.ai-slide-leave-to {
  opacity: 0;
}

.ai-slide-enter-from .ai-panel {
  transform: translateY(16px) scale(0.98);
}

.ai-panel__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s-3);
  padding: var(--s-4) var(--s-5);
  border-bottom: 1px solid var(--c-border);
}

.ai-panel__titles {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.ai-panel__title {
  margin: 0;
  font-size: var(--t-md);
  font-weight: 620;
  letter-spacing: -0.01em;
  color: var(--c-text);
}

.ai-panel__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.ai-chip {
  padding: 1px 8px;
  border-radius: var(--r-full);
  background: var(--c-surface-sunken);
  color: var(--c-text-muted);
  font-size: var(--t-xs);
  line-height: 1.6;
}

.ai-chip--on {
  background: color-mix(in srgb, var(--c-success) 14%, transparent);
  color: var(--c-success);
}

.ai-chip--think {
  background: color-mix(in srgb, var(--c-brand) 12%, transparent);
  color: var(--c-brand);
}

.ai-panel__actions {
  display: flex;
  align-items: center;
  gap: var(--s-1);
  flex-shrink: 0;
}

.ai-clear {
  border: none;
  border-radius: var(--r-sm);
  background: transparent;
  padding: 4px 8px;
  color: var(--c-text-muted);
  font-size: var(--t-sm);
  cursor: pointer;
}

.ai-clear:hover:not(:disabled) {
  background: var(--c-surface-sunken);
  color: var(--c-text);
}

.ai-clear:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.ai-panel__close {
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border: none;
  border-radius: var(--r-full);
  background: transparent;
  color: var(--c-text-muted);
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
}

.ai-panel__close:hover {
  background: var(--c-surface-sunken);
  color: var(--c-text);
}

.ai-panel__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
 padding: var(--s-4) var(--s-5) var(--s-3);
  display: flex;
  flex-direction: column;
  gap: var(--s-4);
}

.ai-state {
  color: var(--c-text-muted);
  font-size: var(--t-sm);
  text-align: center;
  padding: var(--s-8) 0;
}

.ai-empty {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--s-2);
  padding: var(--s-6) 0;
}

.ai-empty__title {
  margin: 0;
  font-size: var(--t-md);
  font-weight: 600;
  color: var(--c-text);
}

.ai-empty__desc {
  margin: 0;
  font-size: var(--t-sm);
  line-height: 1.7;
  color: var(--c-text-muted);
}

.ai-empty__ask {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2);
  margin-top: var(--s-3);
}

.ai-ask {
  padding: 7px 12px;
  border: 1px solid var(--c-border);
  border-radius: var(--r-full);
  background: var(--c-surface);
  color: var(--c-text-secondary);
  font-size: var(--t-sm);
  cursor: pointer;
  transition: border-color 0.15s var(--ease), color 0.15s var(--ease);
}

.ai-ask:hover {
  border-color: var(--c-brand);
  color: var(--c-brand);
}

.ai-msg {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ai-msg--user {
  align-items: flex-end;
}

.ai-bubble {
  max-width: 92%;
  padding: 10px 12px;
  border-radius: var(--r);
  font-size: var(--t-sm);
  line-height: 1.7;
  word-break: break-word;
}

.ai-bubble--user {
  background: var(--c-brand-soft);
  color: var(--c-text);
  white-space: pre-wrap;
}

.ai-bubble--assistant {
  background: var(--c-surface-sunken);
  color: var(--c-text);
}

.ai-bubble--pending {
  background: var(--c-surface-sunken);
  color: var(--c-text-muted);
}

.ai-text {
  margin: 0;
  white-space: pre-wrap;
}

.ai-text + .ai-text {
  margin-top: var(--s-2);
}

.ai-think {
  border-left: 2px solid var(--c-border);
  padding-left: var(--s-3);
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.ai-think__summary {
  cursor: pointer;
  color: var(--c-text-faint);
  list-style: none;
}

.ai-think__summary::-webkit-details-marker {
  display: none;
}

.ai-think__body {
  margin: 6px 0 0;
  line-height: 1.7;
  white-space: pre-wrap;
}

.ai-tools {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.ai-tool {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border-radius: var(--r-full);
  background: color-mix(in srgb, var(--c-brand) 7%, transparent);
  color: var(--c-text-secondary);
  font-size: var(--t-xs);
  width: fit-content;
}

.ai-tool.is-running {
  color: var(--c-brand);
}

.ai-tool__icon {
  font-size: var(--t-xs);
}

.ai-code {
  margin: var(--s-2) 0;
  padding: var(--s-3);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  overflow-x: auto;
  font-family: var(--f-mono);
  font-size: var(--t-xs);
  line-height: 1.6;
  white-space: pre;
}

.ai-table-wrap {
  margin: var(--s-2) 0;
  overflow-x: auto;
}

.ai-table {
  border-collapse: collapse;
  font-size: var(--t-xs);
  min-width: 100%;
}

.ai-table td {
  padding: 5px 10px;
  border: 1px solid var(--c-border);
  white-space: nowrap;
}

.ai-table tr:first-child td {
  background: var(--c-surface);
  font-weight: 600;
  color: var(--c-text);
}

.ai-caret {
  display: inline-block;
  width: 6px;
  height: 14px;
  margin-left: 2px;
  vertical-align: text-bottom;
  background: var(--c-brand);
  animation: ai-blink 1s steps(2, start) infinite;
}

@keyframes ai-blink {
  50% {
    opacity: 0;
  }
}

.ai-picks {
  display: flex;
  flex-direction: column;
  gap: var(--s-2);
  margin-top: var(--s-1);
}

.ai-pick {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: var(--s-3) var(--s-4);
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface);
}

.ai-pick__head {
  display: flex;
  align-items: baseline;
  gap: var(--s-2);
}

.ai-pick__name {
  font-size: var(--t-sm);
  font-weight: 600;
  color: var(--c-text);
}

.ai-pick__code {
  font-family: var(--f-mono);
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.ai-pick__quote {
  display: flex;
  align-items: baseline;
  gap: var(--s-3);
  font-family: var(--f-mono);
  font-size: var(--t-sm);
}

.ai-pick__price {
  color: var(--c-text);
}

.ai-pick__reason {
  margin: 0;
  font-size: var(--t-xs);
  line-height: 1.7;
  color: var(--c-text-muted);
}

.ai-pick__btn {
  align-self: flex-start;
  min-height: 32px;
  padding: 0 14px;
  font-size: var(--t-xs);
}

.ai-alert {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s-2);
  margin: 0 var(--s-5) var(--s-2);
  padding: 8px 10px;
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--stock-up) 10%, transparent);
  color: var(--stock-up);
  font-size: var(--t-xs);
  line-height: 1.6;
}

.ai-alert__text {
  flex: 1;
  min-width: 0;
}

.ai-alert__close,
.ai-alert__retry {
  flex-shrink: 0;
  border: none;
  background: transparent;
  color: inherit;
  font-size: var(--t-xs);
  cursor: pointer;
  padding: 0 2px;
}

.ai-panel__foot {
  display: flex;
  flex-direction: column;
  gap: var(--s-2);
  padding: var(--s-3) var(--s-5) var(--s-4);
  border-top: 1px solid var(--c-border);
}

.ai-quick {
  display: flex;
  gap: 6px;
  overflow-x: auto;
  padding-bottom: 2px;
  scrollbar-width: none;
}

.ai-quick::-webkit-scrollbar {
  display: none;
}

.ai-quick__chip {
  flex-shrink: 0;
  padding: 4px 10px;
  border: 1px solid var(--c-border);
  border-radius: var(--r-full);
  background: var(--c-surface);
  color: var(--c-text-muted);
  font-size: var(--t-xs);
  cursor: pointer;
}

.ai-quick__chip:hover:not(:disabled) {
  border-color: var(--c-brand);
  color: var(--c-brand);
}

.ai-quick__chip:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.ai-input {
  display: flex;
  align-items: flex-end;
  gap: var(--s-2);
}

.ai-input__box {
  flex: 1;
  min-width: 0;
  max-height: 140px;
  padding: 9px 12px;
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface-sunken);
  color: var(--c-text);
  font-family: inherit;
  font-size: var(--t-sm);
  line-height: 1.6;
  resize: none;
  outline: none;
}

.ai-input__box:focus {
  border-color: var(--c-brand);
  box-shadow: 0 0 0 3px var(--c-brand-ring);
}

.ai-input__box:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.ai-input__send {
  flex-shrink: 0;
  min-height: 40px;
  padding: 0 18px;
  font-size: var(--t-sm);
}

.ai-footnote {
  margin: 0;
  font-size: var(--t-xs);
  color: var(--c-text-faint);
  text-align: center;
}

@media (max-width: 720px) {
  .ai-mask {
    padding: var(--s-2);
  }

  .ai-panel {
    width: 100%;
    height: 100%;
    border: none;
    border-radius: 12px;
  }

  .ai-panel__head {
    padding: var(--s-3) var(--s-4);
  }

  .ai-panel__body {
    padding: var(--s-3) var(--s-4);
  }

  .ai-alert {
    margin: 0 var(--s-4) var(--s-2);
  }

  .ai-panel__foot {
    padding: var(--s-3) var(--s-4);
  }
}

@media (prefers-reduced-motion: reduce) {
  .ai-slide-enter-active,
  .ai-slide-leave-active,
  .ai-slide-enter-active .ai-panel,
  .ai-slide-leave-active .ai-panel {
    transition: none;
  }

  .ai-caret {
    animation: none;
  }
}
</style>
