import { UNAUTHORIZED_EVENT, del, get } from './request'

/**
 * AI 分析面板接口。
 *
 * <p>对话接口用 `fetch` + `ReadableStream` 手写 SSE 解析，而不是 axios：
 * XHR 不支持增量读取响应体，axios 只能等整个响应结束再一次性拿到，
 * 那样「思考过程」和正文会同时出现，流式打字机效果就没了。</p>
 */

export interface AiConfig {
  available: boolean
  model: string
  webSearchEnabled: boolean
  thinkingEnabled: boolean
  reasoningEffort: string
}

export interface AiChatMessage {
  role: 'system' | 'user' | 'assistant' | 'tool'
  content: string
  reasoning_content?: string
  tool_calls?: any[]
  tool_call_id?: string
}

export interface AiChatRequest {
  messages: AiChatMessage[]
  stockCode?: string
  stockName?: string
  /**
   * 问财凭证 `v`，由浏览器里的 chameleon.js 生成（见 utils/iwencaiV.ts）。
   *
   * <p>问财接口在服务端直接调用会被 nginx 403，只有在问财域名下用真实浏览器跑脚本
   * 才拿得到 `v`，所以由前端生成、随请求带给后端转发。为空时问财工具会自己提示。</p>
   */
  iwencaiV?: string
  /**
   * 股神模式：开启后后端注入四段式深度分析契约（盈利模式与现金流 → 一月资金动向/量比/换手率
   * → 行业空间/前景/政策 → 综合结论与风险），并要求每一步解释背后的逻辑。
   */
  godMode?: boolean
}

export interface AiPick {
  code: string
  name: string
  price?: number | null
  changePct?: number | null
  reason?: string
}

export type AiChatEventType = 'reasoning' | 'content' | 'tool_call' | 'tool_result' | 'done' | 'error'

export interface AiChatEvent {
  type: AiChatEventType
  text?: string
  name?: string
  args?: any
  result?: string
  message?: string
  picks?: AiPick[]
}

export interface AiChatHandlers {
  onEvent: (event: AiChatEvent) => void
  onError: (message: string) => void
  onClose: () => void
}

const EVENT_TYPES: readonly AiChatEventType[] = ['reasoning', 'content', 'tool_call', 'tool_result', 'done', 'error']

/** 与 request.ts 的 axios baseURL 保持同一套约定：同源时走 /api，跨域时走环境变量 */
function chatEndpoint(): string {
  const base = import.meta.env.VITE_API_BASE || '/api'
  return `${base.replace(/\/+$/, '')}/stock/ai/chat`
}

export function fetchAiConfig(): Promise<AiConfig> {
  return get<AiConfig>('/stock/ai/config')
}

/** 历史消息里的工具轨迹：后端只存名字与参数，中文标签由面板按 TOOL_LABELS 还原 */
export interface AiHistoryTool {
  name: string
  args?: unknown
}

export interface AiHistoryMessage {
  role: 'user' | 'assistant'
  content: string
  reasoning?: string
  tools?: AiHistoryTool[]
  picks?: AiPick[]
}

export interface AiHistory {
  stockCode: string
  stockName: string
  messages: AiHistoryMessage[]
}

/** 读取当前股票（空串=全局助手）的对话历史 */
export function fetchAiHistory(stockCode: string): Promise<AiHistory> {
  return get<AiHistory>('/stock/ai/history', { stockCode })
}

/** 清空当前股票（空串=全局助手）的对话历史 */
export function clearAiHistory(stockCode: string): Promise<void> {
  return del<void>('/stock/ai/history', { stockCode })
}

/**
 * 发起一次流式对话。
 *
 * <p>返回的 `abort` 用于「停止生成」和面板卸载清理；调用后不会再触发 `onEvent`，
 * 但一定会收到一次 `onClose`（幂等），调用方据此统一收尾。</p>
 */
export function chatAi(request: AiChatRequest, handlers: AiChatHandlers): { abort: () => void } {
  const controller = new AbortController()
  let closed = false
  let aborted = false

  function finish() {
    if (closed) {
      return
    }
    closed = true
    handlers.onClose()
  }

  function fail(message: string) {
    if (closed) {
      return
    }
    handlers.onError(message)
    finish()
  }

  /** 事件块之间用空行分隔；兼容 CRLF：调用前已把 `\r\n` 归一成 `\n` */
  function dispatchBlock(block: string) {
    if (closed || !block.trim()) {
      return
    }
    let eventName = 'message'
    const dataLines: string[] = []
    for (const rawLine of block.split('\n')) {
      const line = rawLine.replace(/\r$/, '')
      // 空行、以冒号开头的注释/心跳行直接跳过
      if (!line || line.startsWith(':')) {
        continue
      }
      const colon = line.indexOf(':')
      const field = colon < 0 ? line : line.slice(0, colon)
      let value = colon < 0 ? '' : line.slice(colon + 1)
      if (value.startsWith(' ')) {
        value = value.slice(1)
      }
      if (field === 'event') {
        eventName = value.trim()
      } else if (field === 'data') {
        dataLines.push(value)
      }
    }
    if (dataLines.length === 0) {
      return
    }
    let payload: unknown
    try {
      payload = JSON.parse(dataLines.join('\n'))
    } catch {
      // 单条事件坏掉不能中断整条流：丢掉它，后面的正文还要继续渲染
      return
    }
    const event = normalizeEvent(payload, eventName)
    if (!event) {
      return
    }
    try {
      handlers.onEvent(event)
    } catch {
      // 调用方的渲染异常同样不该把网络流一起带崩
    }
    if (event.type === 'done') {
      finish()
    } else if (event.type === 'error') {
      fail(event.message || 'AI 分析失败')
    }
  }

  /** 处理缓冲区里的完整事件，返回还没凑齐的残余（可能只有半行/半个事件） */
  function consumeEvents(chunk: string): string {
    let buffer = chunk
    for (;;) {
      const index = buffer.indexOf('\n\n')
      if (index < 0) {
        break
      }
      const block = buffer.slice(0, index)
      buffer = buffer.slice(index + 2)
      dispatchBlock(block)
    }
    return buffer
  }

  void (async () => {
    try {
      const response = await fetch(chatEndpoint(), {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream'
        },
        // 登录态在 Cookie 里，跨域直连时必须显式带上
        credentials: 'include',
        body: JSON.stringify(request),
        signal: controller.signal
      })

      if (response.status === 401) {
        notifyUnauthorized()
        fail('登录态已失效，请重新登录管理后台')
        return
      }
      if (!response.ok) {
        fail(await readErrorText(response))
        return
      }
      if (!response.body) {
        fail('当前浏览器不支持流式响应')
        return
      }

      const reader = response.body.getReader()
      // stream: true 是必须的：一个汉字 3 字节，chunk 边界经常正好切在字中间
      const decoder = new TextDecoder('utf-8')
      let buffer = ''

      for (;;) {
        const { done, value } = await reader.read()
        if (done) {
          break
        }
        buffer = consumeEvents(buffer + decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n'))
      }
      // 收尾：把解码器里最后几个字节吐出来；有的服务端最后一条事件不带尾部空行
      buffer += decoder.decode().replace(/\r\n/g, '\n')
      if (buffer.trim()) {
        dispatchBlock(buffer)
      }
      finish()
    } catch (err) {
      // 主动 abort 不是错误：用户点了停止，或者面板被卸载
      if (aborted || isAbortError(err)) {
        finish()
        return
      }
      fail(`AI 请求失败：${errorText(err)}`)
    }
  })()

  return {
    abort: () => {
      aborted = true
      controller.abort()
    }
  }
}

function isEventType(value: unknown): value is AiChatEventType {
  return typeof value === 'string' && (EVENT_TYPES as readonly string[]).includes(value)
}

/** SSE 的 `event:` 优先；有的服务端只把类型塞在 data 里，再退一步按 content 处理 */
function normalizeEvent(payload: unknown, fallbackType: string): AiChatEvent | null {
  if (typeof payload === 'string') {
    const type = isEventType(fallbackType) ? fallbackType : 'content'
    return { type, text: payload }
  }
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
    return null
  }
  const raw = payload as Record<string, unknown>
  const declared = isEventType(raw.type) ? raw.type : isEventType(fallbackType) ? fallbackType : null
  if (!declared) {
    return null
  }
  const event: AiChatEvent = { type: declared }
  if (typeof raw.text === 'string') {
    event.text = raw.text
  }
  if (typeof raw.name === 'string') {
    event.name = raw.name
  }
  if ('args' in raw) {
    event.args = raw.args
  }
  if (typeof raw.result === 'string') {
    event.result = raw.result
  }
  if (typeof raw.message === 'string') {
    event.message = raw.message
  }
  if (Array.isArray(raw.picks)) {
    event.picks = raw.picks as AiPick[]
  }
  return event
}

async function readErrorText(response: Response): Promise<string> {
  const fallback = `AI 请求失败（HTTP ${response.status}）`
  try {
    const text = await response.text()
    if (!text) {
      return fallback
    }
    try {
      const body = JSON.parse(text) as { code?: number; msg?: string }
      if (body && typeof body.msg === 'string' && body.msg) {
        return body.msg
      }
    } catch {
      // 不是 R 包装就按纯文本展示（网关错误页 / nginx 报错）
    }
    return text.slice(0, 200)
  } catch {
    return fallback
  }
}

function isAbortError(err: unknown): boolean {
  return typeof err === 'object' && err !== null && 'name' in err && (err as { name?: unknown }).name === 'AbortError'
}

function errorText(err: unknown): string {
  return err instanceof Error ? err.message : String(err)
}

/**
 * fetch 绕过了 axios 拦截器，401 的登录态广播要在这里补上，
 * 否则 Cookie 过期后页面还停在「已登录」状态。
 */
function notifyUnauthorized() {
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
  }
}
