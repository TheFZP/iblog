import { get, post } from './request'

/**
 * 官网登录态。
 *
 * <p>登录态由管理后台（iblog-admin）建立，存放在同源 Cookie 里；官网只读不写。
 * 已登录时后端会顺带返回用户展示信息，未登录时只有 {@code authenticated: false}。</p>
 */
export interface PortalAuthState {
  authenticated: boolean
  userId?: number
  username?: string
  nickname?: string
  avatar?: string
}

/**
 * 读取当前 Cookie 对应的登录态。无需登录，未登录时返回 authenticated = false。
 */
export function fetchAuthState() {
  return get<PortalAuthState>('/portal/auth/state')
}

/**
 * 消费后台签发的一次性 ticket，成功后后端会 Set-Cookie 建立官网登录态。
 * 票据无效时业务码为 401（由 request.ts 统一广播未授权事件）。
 */
export function ticketLogin(ticket: string) {
  return post<PortalAuthState>('/portal/auth/ticket-login', { ticket })
}
