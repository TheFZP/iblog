/**
 * 问财验证凭证（Cookie `v`）的浏览器侧生成器。
 *
 * <p>为什么必须在浏览器里生成：问财接口要求带 Cookie `v`，这个值由官方
 * `chameleon.js` 在页面里算出来，算法依赖真实浏览器环境（canvas、原生 API 时序等），
 * 在 Node 或服务端沙箱里跑不出来（实测：纯 vm 沙箱注入完整 document/navigator 桩，
 * 只会写入 `checkcookie`，`v` 永远为空）。所以流程是「浏览器算 v → 交给自己的后端
 * 去转发请求」，后端只做代理，不保存这个凭证。</p>
 *
 * <p>`v` 的有效期约 30 分钟，且同一值连续请求 6 次左右会被问财风控 403；
 * 因此这里只做短缓存，并在后端回 429 时清缓存重新生成。</p>
 */

const SCRIPT_SRC = '/chameleon.js'
/** 缓存时长：比实测有效期短一截，避免拿到临期值就接连 403。 */
const V_TTL = 8 * 60 * 1000
/** 注入后最多等多久读到 cookie（chameleon.js 是异步写 cookie 的）。 */
const WAIT_MS = 8000
const POLL_MS = 200

let cached = { value: '', ts: 0 }
let injected = false

function readVCookie(): string {
  const match = document.cookie.match(/(?:^|;\s*)v=([^;]+)/)
  return match ? match[1] : ''
}

/**
 * 确保拿到一个可用的 `v`。
 *
 * <p>失败时抛错而不是返回空串：空串会让后端问财接口直接挨 nginx 403，
 * 用户看到的是一条看不出原因的失败；这里明确告诉他是凭证没生成出来。</p>
 */
export async function ensureIwencaiV(): Promise<string> {
  if (cached.value && Date.now() - cached.ts < V_TTL) {
    return cached.value
  }
  if (typeof document === 'undefined') {
    throw new Error('当前环境不支持获取问财凭证')
  }
  if (!injected) {
    const script = document.createElement('script')
    script.src = SCRIPT_SRC
    script.async = true
    document.head.appendChild(script)
    injected = true
  }
  const start = Date.now()
  while (Date.now() - start < WAIT_MS) {
    const value = readVCookie()
    if (value) {
      cached = { value, ts: Date.now() }
      return value
    }
    await new Promise((resolve) => setTimeout(resolve, POLL_MS))
  }
  throw new Error('未能生成问财验证凭证，请刷新页面后重试')
}

/** 凭证被判失效（403/429）后调用：下次会重新注入脚本读新值。 */
export function resetIwencaiV(): void {
  cached = { value: '', ts: 0 }
  injected = false
}

/**
 * 尝试取凭证，取不到返回空串。
 *
 * <p>对话入口用这个：用户可能只是想聊聊行情，并不打算选股，没必要因为
 * 问财凭证生成失败就把整条对话拦住——空 v 传过去，后端会在真正调用
 * stock_screener 时才提示。</p>
 */
export async function tryIwencaiV(): Promise<string> {
  try {
    return await ensureIwencaiV()
  } catch {
    return ''
  }
}
