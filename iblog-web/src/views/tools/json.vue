<template>
  <div class="json-app">
    <header class="top">
      <a class="top__back" href="/tools">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M15 5l-7 7 7 7" />
        </svg>
        工具箱
      </a>
      <span class="top__divider" aria-hidden="true" />
      <h1 class="top__title">JSON 在线解析</h1>
      <span class="top__status" :class="statusClass">{{ statusText }}</span>

      <div class="top__actions">
        <button
          class="icon-btn"
          type="button"
          :aria-label="theme === 'dark' ? '切换到浅色模式' : '切换到深色模式'"
          @click="toggleTheme"
        >
          <svg v-if="theme === 'dark'" viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="12" cy="12" r="4.2" />
            <path d="M12 2.6v2M12 19.4v2M2.6 12h2M19.4 12h2M5.4 5.4l1.4 1.4M17.2 17.2l1.4 1.4M18.6 5.4l-1.4 1.4M6.8 17.2l-1.4 1.4" />
          </svg>
          <svg v-else viewBox="0 0 24 24" aria-hidden="true">
            <path d="M20.6 14.4A8.7 8.7 0 0 1 9.6 3.4a8.6 8.6 0 1 0 11 11Z" />
          </svg>
        </button>
      </div>
    </header>

    <div class="toolbar">
      <div class="toolbar__group">
        <button class="btn" type="button" :disabled="!hasInput" @click="format">格式化</button>
        <button class="btn btn--ghost" type="button" :disabled="!hasInput" @click="minify">压缩</button>
        <button class="btn btn--ghost" type="button" :disabled="!hasInput" @click="sortKeys">排序键</button>
      </div>

      <div class="toolbar__group">
        <span class="toolbar__label">缩进</span>
        <div class="seg" role="group" aria-label="缩进">
          <button
            v-for="opt in INDENTS"
            :key="String(opt.value)"
            type="button"
            class="seg__btn"
            :class="{ 'seg__btn--on': indent === opt.value }"
            :aria-pressed="indent === opt.value"
            @click="setIndent(opt.value)"
          >
            {{ opt.label }}
          </button>
        </div>
      </div>

      <div class="toolbar__group toolbar__group--end">
        <button class="btn btn--ghost" type="button" @click="loadSample">示例</button>
        <button class="btn btn--ghost" type="button" :disabled="!hasInput" @click="clearAll">清空</button>
        <button class="btn btn--ghost" type="button" :disabled="!output" @click="copyOut">复制</button>
        <button class="btn btn--ghost" type="button" :disabled="!output" @click="downloadOut">下载</button>
      </div>
    </div>

    <div class="panes">
      <section class="pane">
        <header class="pane__head">
          <span class="pane__title">输入</span>
          <span class="pane__meta">{{ inputMeta }}</span>
        </header>
        <textarea
          v-model="input"
          class="pane__editor"
          spellcheck="false"
          autocomplete="off"
          autocapitalize="off"
          wrap="off"
          placeholder="在这里粘贴 JSON，Ctrl / ⌘ + Enter 直接格式化"
          @keydown="onEditorKeydown"
        />
      </section>

      <section class="pane">
        <header class="pane__head">
          <span class="pane__title">结果</span>
          <span class="pane__meta">{{ outputMeta }}</span>
        </header>

        <div v-if="error" class="pane__error">
          <p class="error__title">{{ error.title }}</p>
          <p class="error__detail">{{ error.detail }}</p>
          <p v-if="error.where" class="error__where">{{ error.where }}</p>
        </div>

        <pre v-else-if="output" class="pane__code" v-html="highlighted" />

        <div v-else class="pane__empty">
          <p>左边粘贴后按「格式化」，或点上面的「示例」看效果。</p>
        </div>
      </section>
    </div>

    <footer class="status">
      <span class="status__msg">{{ statusLine }}</span>
      <span v-if="stats" class="status__stats">{{ stats }}</span>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { initTheme, toggleTheme, useTheme } from '@/composables/useSite'

const { theme } = useTheme()
// 独立页面：index.html 内联脚本已经按 localStorage 设好 data-theme，这里同步一下 ref 让切换按钮状态正确
onMounted(initTheme)

/* ------------------------------------------------------------------ */
/*  状态                                                               */
/* ------------------------------------------------------------------ */
const input = ref('')
const output = ref('')
const error = ref<JsonError | null>(null)
const mode = ref<'pretty' | 'compact'>('pretty')
const indent = ref<Indent>(2)

interface IndentOption {
  value: Indent
  label: string
}
type Indent = 2 | 4 | 'tab'
const INDENTS: IndentOption[] = [
  { value: 2, label: '2 空格' },
  { value: 4, label: '4 空格' },
  { value: 'tab', label: 'Tab' }
]

interface JsonError {
  title: string
  detail: string
  where: string
}

function indentArg(): string | number {
  return indent.value === 'tab' ? '\t' : indent.value
}

const hasInput = computed(() => input.value.trim().length > 0)

/* ------------------------------------------------------------------ */
/*  核心：解析 / 格式化 / 压缩                                         */
/* ------------------------------------------------------------------ */
function run(nextMode: 'pretty' | 'compact') {
  const raw = input.value
  if (!raw.trim()) {
    output.value = ''
    error.value = null
    return
  }
  mode.value = nextMode
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch (e) {
    error.value = describeError(e instanceof Error ? e.message : String(e), raw)
    output.value = ''
    return
  }
  error.value = null
  try {
    const space = nextMode === 'pretty' ? indentArg() : undefined
    output.value = JSON.stringify(parsed, null, space)
  } catch (e) {
    // 极深嵌套可能超出引擎限制
    error.value = {
      title: '结构太深，无法重新序列化',
      detail: e instanceof Error ? e.message : String(e),
      where: ''
    }
    output.value = ''
  }
}

function format() {
  run('pretty')
}
function minify() {
  run('compact')
}
function sortKeys() {
  const raw = input.value
  if (!raw.trim()) return
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch (e) {
    error.value = describeError(e instanceof Error ? e.message : String(e), raw)
    output.value = ''
    return
  }
  error.value = null
  output.value = JSON.stringify(sortDeep(parsed), null, indentArg())
  mode.value = 'pretty'
}
function setIndent(value: Indent) {
  indent.value = value
  // 已出结果时按新缩进重排
  if (output.value && !error.value) run(mode.value)
}
function clearAll() {
  input.value = ''
  output.value = ''
  error.value = null
}
function loadSample() {
  input.value = SAMPLE
  run('pretty')
}

/* ------------------------------------------------------------------ */
/*  复制 / 下载                                                        */
/* ------------------------------------------------------------------ */
let flashTimer: number | undefined
const flash = ref('')
function flashMessage(msg: string) {
  flash.value = msg
  window.clearTimeout(flashTimer)
  flashTimer = window.setTimeout(() => {
    flash.value = ''
  }, 1800)
}
onUnmounted(() => window.clearTimeout(flashTimer))

async function copyOut() {
  if (!output.value) return
  try {
    await navigator.clipboard.writeText(output.value)
    flashMessage('已复制')
  } catch {
    // 非安全上下文（如局域网 http）兜底
    const ta = document.createElement('textarea')
    ta.value = output.value
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    try {
      document.execCommand('copy')
      flashMessage('已复制')
    } catch {
      flashMessage('复制失败，请手动选择')
    }
    document.body.removeChild(ta)
  }
}
function downloadOut() {
  if (!output.value) return
  const blob = new Blob([output.value], { type: 'application/json;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `json-${new Date().toISOString().slice(0, 10)}.json`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
  flashMessage('已下载')
}

function onEditorKeydown(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
    event.preventDefault()
    format()
  }
}

/* ------------------------------------------------------------------ */
/*  统计 & 元信息                                                      */
/* ------------------------------------------------------------------ */
const inputMeta = computed(() => measure(input.value))
const outputMeta = computed(() => (output.value ? measure(output.value) : ''))
const stats = computed(() => {
  if (!output.value || error.value) return ''
  const s = collectStats(parseForStats(output.value))
  return `${s.keys} 个键 · 嵌套 ${s.depth} 层`
})

function measure(text: string) {
  if (!text) return ''
  const bytes = new TextEncoder().encode(text).length
  const lines = text.split('\n').length
  const size = bytes >= 1024 ? `${(bytes / 1024).toFixed(1)} KB` : `${bytes} B`
  return `${size} · ${lines} 行`
}

const statusLine = computed(() => {
  if (flash.value) return flash.value
  if (error.value) return [error.value.where, error.value.title].filter(Boolean).join(' · ')
  if (output.value) return '解析通过'
  return '等待输入'
})
const statusClass = computed(() => {
  if (error.value) return 'is-error'
  if (output.value) return 'is-ok'
  return 'is-idle'
})
const statusText = computed(() => {
  if (error.value) return '解析失败'
  if (output.value) return '解析通过'
  return '等待输入'
})

/* ------------------------------------------------------------------ */
/*  语法高亮                                                           */
/* ------------------------------------------------------------------ */
const TOKEN_RE = /("(?:\\.|[^"\\])*")(\s*:)?|\b(?:true|false)\b|\bnull\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g

const highlighted = computed(() => {
  const raw = output.value
  if (!raw) return ''
  let out = ''
  let last = 0
  let m: RegExpExecArray | null
  TOKEN_RE.lastIndex = 0
  while ((m = TOKEN_RE.exec(raw)) !== null) {
    if (m.index > last) out += escapeHtml(raw.slice(last, m.index))
    const token = m[0]
    if (m[1]) {
      // 字符串：后面跟冒号 → 键名
      out += `<span class="tk-key">${escapeHtml(m[1])}</span>`
      if (m[2]) out += escapeHtml(m[2])
    } else if (token === 'true' || token === 'false') {
      out += `<span class="tk-bool">${token}</span>`
    } else if (token === 'null') {
      out += `<span class="tk-null">${token}</span>`
    } else {
      out += `<span class="tk-num">${token}</span>`
    }
    last = m.index + token.length
  }
  if (last < raw.length) out += escapeHtml(raw.slice(last))
  return out
})

function escapeHtml(text: string): string {
  return text.replace(/[&<>]/g, (c) => (c === '&' ? '&amp;' : c === '<' ? '&lt;' : '&gt;'))
}

/* ------------------------------------------------------------------ */
/*  错误解释（中文，并定位行列）                                       */
/* ------------------------------------------------------------------ */
const RULES: Array<[RegExp, string]> = [
  [/Unexpected end of JSON input/i, 'JSON 不完整：内容在中途结束了，检查括号是否配对'],
  [/Unexpected non-whitespace character after JSON/i, 'JSON 已经结束，但后面还有多余内容'],
  [/Expected ',' or '}' after property value/i, '对象里两个键值对之间缺少逗号'],
  [/Expected ',' or ']' after array element/i, '数组元素之间缺少逗号'],
  [/Expected property name or '}'/i, '对象的键必须是双引号包裹的字符串'],
  [/Expected double-quoted property name/i, '对象的键必须是双引号包裹的字符串'],
  [/Unterminated string/i, '字符串没有闭合，缺少结尾的双引号'],
  [/Bad control character/i, '字符串里有未转义的控制字符（如换行、制表符）'],
  [/Bad escaped character/i, '存在非法的转义字符'],
  [/Unexpected token/i, '出现了不该出现的字符'],
  [/Unexpected number/i, '数字格式不对'],
  [/exceeding the maximum/i, '嵌套层级过深，超出引擎限制']
]

function translate(core: string): string {
  for (const [re, msg] of RULES) {
    if (re.test(core)) return msg
  }
  return 'JSON 解析失败'
}

function describeError(raw: string, text: string): JsonError {
  const posMatch = /at position (\d+)/.exec(raw)
  const pos = posMatch ? Number(posMatch[1]) : -1
  const lineCol = /\(line (\d+) column (\d+)\)/.exec(raw)
  let line = lineCol ? Number(lineCol[1]) : -1
  let column = lineCol ? Number(lineCol[2]) : -1
  if (pos >= 0 && (line < 0 || column < 0)) {
    const upto = text.slice(0, pos)
    line = upto.split('\n').length
    column = pos - upto.lastIndexOf('\n')
  }
  const core = raw
    .replace(/\s*in JSON at position \d+.*$/i, '')
    .replace(/\s*\(line \d+ column \d+\)\s*$/i, '')
  return {
    title: translate(core),
    detail: core,
    where: line > 0 ? `第 ${line} 行 第 ${column} 列` : ''
  }
}

/* ------------------------------------------------------------------ */
/*  辅助：递归排序键 / 统计                                            */
/* ------------------------------------------------------------------ */
function sortDeep(node: unknown): unknown {
  if (Array.isArray(node)) return node.map(sortDeep)
  if (node && typeof node === 'object') {
    const out: Record<string, unknown> = {}
    for (const k of Object.keys(node as Record<string, unknown>).sort()) {
      out[k] = sortDeep((node as Record<string, unknown>)[k])
    }
    return out
  }
  return node
}

function parseForStats(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

function collectStats(root: unknown): { keys: number; depth: number } {
  let keys = 0
  let depth = 0
  const stack: Array<[unknown, number]> = [[root, 1]]
  while (stack.length) {
    const [node, d] = stack.pop() as [unknown, number]
    if (d > depth) depth = d
    if (Array.isArray(node)) {
      for (const item of node) stack.push([item, d + 1])
    } else if (node && typeof node === 'object') {
      for (const val of Object.values(node as Record<string, unknown>)) {
        keys++
        stack.push([val, d + 1])
      }
    }
  }
  return { keys, depth }
}

const SAMPLE = `{
  "name": "iblog",
  "stars": 42,
  "private": false,
  "tags": ["vue", "spring-boot", "quant"],
  "author": { "name": "TheFZP", "city": "广州" },
  "deps": {
    "vue": "^3.5.0",
    "lightweight-charts": "^5.2.1"
  }
}`
</script>

<style scoped>
.json-app {
  display: flex;
  flex-direction: column;
  height: 100vh;
  height: 100dvh;
  background: var(--c-bg);
  color: var(--c-text);
}

/* 顶部条 */
.top {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  padding: var(--s-3) var(--s-5);
  border-bottom: 1px solid var(--c-border);
  flex: 0 0 auto;
}
.top__back {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--c-text-secondary);
  font-size: var(--t-sm);
}
.top__back svg {
  width: 16px;
  height: 16px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.top__back:hover {
  color: var(--c-brand);
}
.top__divider {
  width: 1px;
  height: 18px;
  background: var(--c-border-strong);
}
.top__title {
  font-size: var(--t-md);
  font-weight: 640;
  letter-spacing: -0.01em;
}
.top__status {
  font-size: var(--t-xs);
  padding: 2px 10px;
  border-radius: var(--r-full);
  background: var(--c-surface-sunken);
  color: var(--c-text-muted);
}
.top__status.is-ok {
  background: color-mix(in srgb, var(--c-success) 16%, transparent);
  color: var(--c-success);
}
.top__status.is-error {
  background: color-mix(in srgb, var(--stock-up) 16%, transparent);
  color: var(--stock-up);
}
.top__actions {
  margin-left: auto;
  display: flex;
  gap: var(--s-2);
}
.icon-btn {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface);
  color: var(--c-text-secondary);
  cursor: pointer;
  transition: border-color 0.15s var(--ease), color 0.15s var(--ease);
}
.icon-btn:hover {
  border-color: var(--c-border-strong);
  color: var(--c-brand);
}
.icon-btn svg {
  width: 20px;
  height: 20px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}

/* 工具栏 */
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s-3) var(--s-5);
  padding: var(--s-3) var(--s-5);
  border-bottom: 1px solid var(--c-border);
  background: var(--c-surface);
  flex: 0 0 auto;
}
.toolbar__group {
  display: flex;
  align-items: center;
  gap: var(--s-2);
}
.toolbar__group--end {
  margin-left: auto;
}
.toolbar__label {
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}
.btn {
  height: 36px;
  padding: 0 var(--s-4);
  border: 1px solid transparent;
  border-radius: var(--r);
  background: var(--c-brand);
  color: #fff;
  font-size: var(--t-sm);
  font-weight: 540;
  cursor: pointer;
  transition: background-color 0.15s var(--ease), border-color 0.15s var(--ease), opacity 0.15s var(--ease);
}
.btn:hover:not(:disabled) {
  background: var(--c-brand-hover);
}
.btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.btn--ghost {
  background: transparent;
  border-color: var(--c-border);
  color: var(--c-text-secondary);
}
.btn--ghost:hover:not(:disabled) {
  background: var(--c-surface-sunken);
  color: var(--c-text);
  border-color: var(--c-border-strong);
}
.seg {
  display: inline-flex;
  padding: 2px;
  gap: 2px;
  border-radius: var(--r-full);
  background: var(--c-surface-sunken);
}
.seg__btn {
  padding: 4px 12px;
  border: 0;
  border-radius: var(--r-full);
  background: transparent;
  color: var(--c-text-muted);
  font-size: var(--t-xs);
  cursor: pointer;
}
.seg__btn--on {
  background: var(--c-surface);
  color: var(--c-text);
  box-shadow: var(--e-sm);
}

/* 双栏 */
.panes {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 1px;
  background: var(--c-border);
  flex: 1 1 auto;
  min-height: 0;
}
.pane {
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--c-bg);
}
.pane__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-3);
  padding: var(--s-2) var(--s-4);
  border-bottom: 1px solid var(--c-border);
  background: var(--c-surface);
  flex: 0 0 auto;
}
.pane__title {
  font-size: var(--t-xs);
  font-weight: 620;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--c-text-secondary);
}
.pane__meta {
  font-size: var(--t-xs);
  color: var(--c-text-faint);
  font-variant-numeric: tabular-nums;
}
.pane__editor {
  flex: 1 1 auto;
  min-height: 0;
  width: 100%;
  border: 0;
  outline: none;
  resize: none;
  padding: var(--s-4);
  background: var(--c-bg);
  color: var(--c-text);
  font-family: var(--f-mono);
  font-size: var(--t-sm);
  line-height: 1.6;
  tab-size: 2;
}
.pane__code {
  flex: 1 1 auto;
  min-height: 0;
  margin: 0;
  padding: var(--s-4);
  overflow: auto;
  background: var(--c-bg);
  color: var(--c-text);
  font-family: var(--f-mono);
  font-size: var(--t-sm);
  line-height: 1.6;
  white-space: pre;
  tab-size: 2;
}
.pane__empty,
.pane__error {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
  padding: var(--s-4);
}
.pane__empty {
  display: grid;
  place-items: center;
  color: var(--c-text-faint);
  font-size: var(--t-sm);
}
.pane__error {
  color: var(--stock-up);
}
.error__title {
  font-weight: 620;
  margin-bottom: var(--s-2);
}
.error__detail {
  font-family: var(--f-mono);
  font-size: var(--t-xs);
  color: var(--c-text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
}
.error__where {
  margin-top: var(--s-2);
  font-size: var(--t-sm);
}

/* 语法高亮配色：沿用设计系统语义色 */
.pane__code :deep(.tk-key) {
  color: var(--c-brand);
}
.pane__code :deep(.tk-str) {
  color: var(--c-success);
}
.pane__code :deep(.tk-num) {
  color: var(--c-accent);
}
.pane__code :deep(.tk-bool) {
  color: var(--c-text);
  font-weight: 600;
}
.pane__code :deep(.tk-null) {
  color: var(--c-text-faint);
  font-style: italic;
}

/* 状态栏 */
.status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-4);
  padding: var(--s-2) var(--s-5);
  border-top: 1px solid var(--c-border);
  background: var(--c-surface);
  font-size: var(--t-xs);
  color: var(--c-text-muted);
  flex: 0 0 auto;
}
.status__stats {
  font-variant-numeric: tabular-nums;
}

@media (max-width: 860px) {
  .panes {
    grid-template-columns: 1fr;
    grid-template-rows: 1fr 1fr;
  }
}
</style>
