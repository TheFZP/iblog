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
