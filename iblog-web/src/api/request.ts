import axios, { type AxiosInstance } from 'axios'

/**
 * 未授权事件名。
 *
 * <p>行情等接口需要管理后台的登录态。API 层不直接依赖 UI 状态，只广播事件，
 * 由 useAuth 监听后清空登录态 —— 避免 request ← composable 的循环引用。</p>
 */
export const UNAUTHORIZED_EVENT = 'iblog:unauthorized'

const service: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 15000,
  // 官网与后台同源时 Cookie 本来就会带上；跨域直连时显式声明，避免浏览器丢弃凭证
  withCredentials: true
})

function notifyUnauthorized() {
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
  }
}

service.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code === 200) {
      return res.data
    }
    // 业务码 401：HTTP 仍是 200，但登录态已失效（未登录 / Cookie 过期）
    if (res.code === 401) {
      notifyUnauthorized()
    }
    return Promise.reject(new Error(res.msg || '请求失败'))
  },
  (error) => {
    // HTTP 401：网关或鉴权层直接拒绝，同样视为登录态失效
    if (error?.response?.status === 401) {
      notifyUnauthorized()
    }
    return Promise.reject(error)
  }
)

export function get<T = any>(url: string, params?: Record<string, any>): Promise<T> {
  return service.get(url, { params }) as unknown as Promise<T>
}

export function post<T = any>(url: string, data?: any): Promise<T> {
  return service.post(url, data) as unknown as Promise<T>
}

export function del<T = any>(url: string, params?: Record<string, any>): Promise<T> {
  return service.delete(url, { params }) as unknown as Promise<T>
}

export default service
