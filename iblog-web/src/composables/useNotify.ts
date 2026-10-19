import { ref, computed, watch } from 'vue'

/**
 * 浏览器通知封装。
 *
 * <p>桌面版用 Tauri 的 {@code sendNotification} 发 Windows 原生通知；网页端对应的是
 * 浏览器的 Notification API，两者能力差别很大，需要明确：</p>
 * <ul>
 *   <li>必须由用户手势触发权限申请，不能自动弹（否则被浏览器直接拒绝且不再询问）</li>
 *   <li>页面关闭后完全收不到 —— 真正的后台提醒需要 Service Worker + 推送服务，
 *       超出本次范围。页面开着（哪怕在后台标签页）时能正常收到</li>
 *   <li>页面不在前台时，额外用 document.title 闪烁兜底</li>
 * </ul>
 */

export type NotifyPermission = 'default' | 'granted' | 'denied' | 'unsupported'

const permission = ref<NotifyPermission>(readPermission())

function readPermission(): NotifyPermission {
  if (typeof Notification === 'undefined') {
    return 'unsupported'
  }
  return Notification.permission as NotifyPermission
}

/** 申请通知权限。必须在用户点击等手势回调里调用，否则浏览器会忽略。 */
async function requestNotifyPermission(): Promise<NotifyPermission> {
  if (typeof Notification === 'undefined') {
    permission.value = 'unsupported'
    return 'unsupported'
  }
  if (Notification.permission === 'granted') {
    permission.value = 'granted'
    return 'granted'
  }
  if (Notification.permission === 'denied') {
    // 已拒绝时浏览器不会再弹窗，只能引导用户去站点设置里改
    permission.value = 'denied'
    return 'denied'
  }
  try {
    const result = await Notification.requestPermission()
    permission.value = result as NotifyPermission
    return permission.value
  } catch {
    permission.value = 'denied'
    return 'denied'
  }
}

/** 页面标题闪烁，页面不在前台时的兜底提醒 */
let flashTimer: number | null = null
let flashOriginalTitle = ''

function startTitleFlash(text: string) {
  stopTitleFlash()
  if (typeof document === 'undefined') {
    return
  }
  flashOriginalTitle = document.title
  let on = false
  flashTimer = window.setInterval(() => {
    on = !on
    document.title = on ? text : flashOriginalTitle
  }, 1200)
}

function stopTitleFlash() {
  if (flashTimer !== null) {
    window.clearInterval(flashTimer)
    flashTimer = null
    if (flashOriginalTitle) {
      document.title = flashOriginalTitle
    }
  }
}

/** 用户回到页面即停止闪烁，避免标题一直抖 */
if (typeof document !== 'undefined') {
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
      stopTitleFlash()
    }
  })
}

/**
 * 发送一条提醒。
 *
 * @param tag 通知合并标签。相同 tag 的新通知会替换旧通知，避免连续刷新堆成一屏；
 *            但不同标的/不同原因必须给不同 tag，否则「A 突破」会被「B 止损」顶掉，
 *            用户只能看到最后一条。
 * @returns 是否成功送达（权限被拒返回 false，调用方应停止后续发送）
 */
async function sendNotification(title: string, body: string, tag = 'iblog-stock-alert'): Promise<boolean> {
  if (permission.value !== 'granted') {
    permission.value = readPermission()
  }

  let sent = false
  if (permission.value === 'granted') {
    try {
      new Notification(title, { body, tag, icon: '/favicon.ico' })
      sent = true
    } catch {
      sent = false
    }
  }

  // 页面不在前台时闪标题：通知可能被系统静音，标题抖动至少不会被忽略
  if (typeof document !== 'undefined' && document.visibilityState !== 'visible') {
    startTitleFlash(`🔔 ${title}`)
  }
  return sent
}

export function useNotify() {
  return {
    permission,
    canNotify: computed(() => permission.value === 'granted'),
    requestNotifyPermission,
    sendNotification,
    stopTitleFlash
  }
}
