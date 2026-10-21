import { ref } from 'vue'
import { fetchAuthState, ticketLogin, type PortalAuthState } from '@/api/auth'
import { UNAUTHORIZED_EVENT } from '@/api/request'

/** 登录用户用于展示的信息，未登录时整体为 null */
export interface PortalUser {
  userId?: number
  username?: string
  nickname?: string
  avatar?: string
}

/*
 * 登录态做成模块级单例：导航栏、路由守卫、行情页门禁、提醒巡查器看到的是同一份状态，
 * 任何一处校验完，其他位置立刻生效，不需要各自再打一次 /portal/auth/state。
 */
const authenticated = ref(false)
const user = ref<PortalUser | null>(null)
/** 是否已经完成过一次校验（无论结果如何）。未完成时界面显示「校验中」而不是「未登录」 */
const checked = ref(false)
const checking = ref(false)

/** 并发去重：多个守卫/组件同时 ensureAuth 时只发一个请求 */
let inflight: Promise<void> | null = null

function applyState(state: PortalAuthState | null | undefined) {
  const ok = state?.authenticated === true
  authenticated.value = ok
  user.value = ok
    ? {
        userId: state?.userId,
        username: state?.username,
        nickname: state?.nickname,
        avatar: state?.avatar
      }
    : null
  checked.value = true
}

/**
 * 确保登录态已确认。首次调用或 force 时请求后端，其余情况直接命中缓存。
 *
 * <p>网络异常也按「未登录」收尾并标记 checked，避免路由守卫一直等待、
 * 或者页面永远停在「校验中」。</p>
 */
export async function ensureAuth(force = false): Promise<void> {
  if (!force && checked.value) {
    return
  }
  if (inflight) {
    return inflight
  }
  checking.value = true
  inflight = fetchAuthState()
    .then((state) => {
      applyState(state)
    })
    .catch(() => {
      authenticated.value = false
      user.value = null
      checked.value = true
    })
    .finally(() => {
      checking.value = false
      inflight = null
    })
  return inflight
}

/**
 * 消费后台带来的一次性 ticket。失败（过期 / 伪造 / 网络异常）静默返回 false，
 * 由调用方决定后续跳转，不在这里抛错。
 */
export async function consumeTicket(ticket: string): Promise<boolean> {
  if (!ticket) {
    return false
  }
  try {
    const state = await ticketLogin(ticket)
    applyState(state)
    return authenticated.value
  } catch {
    return false
  }
}

/**
 * 清空登录态。已被服务端判定未授权（401）时调用。
 *
 * <p>checked 置 true 而不是 false：此刻已经知道是未登录，再显示「校验中」只会让人干等；
 * Gate 也会因此立刻切到锁定卡片。</p>
 */
export function reset() {
  authenticated.value = false
  user.value = null
  checked.value = true
}

// 行情等接口返回 401 时，request.ts 只负责广播事件，这里统一把状态清掉。
// 用事件解耦是为了让 API 层不反向依赖 UI 状态，也避免循环引用。
if (typeof window !== 'undefined') {
  window.addEventListener(UNAUTHORIZED_EVENT, () => {
    reset()
  })
}

export function useAuth() {
  return { authenticated, user, checked, checking, ensureAuth, consumeTicket, reset }
}
