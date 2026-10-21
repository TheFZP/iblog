import { get, post } from './request'

/** 登录 */
export interface LoginParams {
  username: string
  password: string
}

export interface LoginResult {
  token: string
  userId: number
  username: string
  nickname: string
  avatar: string
}

export function login(data: LoginParams) {
  return post<LoginResult>('/admin/auth/login', data)
}

export function logout() {
  return post<void>('/admin/auth/logout')
}

export function getUserInfo() {
  return get<any>('/admin/auth/info')
}

/** 官网行情登录票据：官网与本后台网址不同、无法共享 Cookie 时，用一次性票据完成免登录跳转 */
export interface StockTicket {
  ticket: string
  expiresIn: number
}

export function getStockTicket() {
  return post<StockTicket>('/admin/auth/ticket')
}
