import axios, { type AxiosInstance } from 'axios'

const service: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 15000
})

service.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code === 200) {
      return res.data
    }
    return Promise.reject(new Error(res.msg || '请求失败'))
  },
  (error) => Promise.reject(error)
)

export function get<T = any>(url: string, params?: Record<string, any>): Promise<T> {
  return service.get(url, { params }) as unknown as Promise<T>
}

export function post<T = any>(url: string, data?: any): Promise<T> {
  return service.post(url, data) as unknown as Promise<T>
}

export default service
