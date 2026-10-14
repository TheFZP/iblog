import axios, { type AxiosInstance } from 'axios'
import { ElMessage } from 'element-plus'

const TOKEN_KEY = 'iblog_token'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

const service: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 15000
})

service.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

service.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code === 200) {
      return res.data
    }
    if (res.code === 401) {
      clearToken()
      ElMessage.error(res.msg || '登录已过期，请重新登录')
      // 交给路由守卫做跳转，避免这里重复引入 router
      setTimeout(() => {
        window.location.href = '/login'
      }, 600)
      return Promise.reject(new Error(res.msg || 'Unauthorized'))
    }
    ElMessage.error(res.msg || '请求失败')
    return Promise.reject(new Error(res.msg || 'Error'))
  },
  (error) => {
    const status = error?.response?.status
    if (status === 401) {
      clearToken()
      window.location.href = '/login'
    } else {
      ElMessage.error(error?.message || '网络异常，请稍后重试')
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

export function put<T = any>(url: string, data?: any): Promise<T> {
  return service.put(url, data) as unknown as Promise<T>
}

export function del<T = any>(url: string, params?: Record<string, any>): Promise<T> {
  return service.delete(url, { params }) as unknown as Promise<T>
}

export default service
