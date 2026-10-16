/**
 * 高德地图跳转。
 *
 * 用的是高德 Web URI API（https://uri.amap.com/…），手机上会自动唤起高德 App，
 * 没装 App 或 PC 端则落到高德网页版，不需要申请 key，也不依赖任何 SDK。
 *
 * ⚠️ 坐标系：必须传 gcj02（火星坐标）。若误把 GPS 原始 wgs84 坐标传进来，
 *    位置会偏几百米 —— 所以后台录入时就按高德拾取器（gcj02）取值。
 */

export interface AmapTarget {
  name: string
  address?: string | null
  longitude?: number | null
  latitude?: number | null
  poiId?: string | null
}

/** 来源标识，会出现在高德页面里，便于回溯是从哪个站跳来的。 */
const SRC = 'iblog'

function hasCoord(target: AmapTarget): boolean {
  return target.longitude != null && target.latitude != null
}

function hasPoi(target: AmapTarget): boolean {
  return !!target.poiId && target.poiId.trim().length > 0
}

/** 是否具备跳转条件：有 POI ID 或有完整经纬度。只有文字地址不算。 */
export function canLocate(target: AmapTarget): boolean {
  return hasPoi(target) || hasCoord(target)
}

/**
 * 拼高德 URI。
 *
 * @param callNative 是否尝试唤起 App。
 *   仅移动端该传 true —— PC 上传了也没用，反而多一次无谓跳转。
 */
export function buildAmapUrl(target: AmapTarget, callNative: boolean): string {
  const params = new URLSearchParams()
  params.set('src', SRC)

  if (hasPoi(target)) {
    params.set('poiid', target.poiId!.trim())
  } else if (hasCoord(target)) {
    // 顺序不能反：高德要的是 经度,纬度
    params.set('position', `${target.longitude},${target.latitude}`)
    params.set('coordinate', 'gaode')
  } else {
    // 兜底：只有文字地址时走关键词搜索
    params.set('keyword', [target.name, target.address].filter(Boolean).join(' '))
    if (callNative) {
      params.set('callnative', '1')
    }
    return `https://uri.amap.com/search?${params.toString()}`
  }

  if (callNative) {
    params.set('callnative', '1')
  }
  if (target.name) {
    params.set('name', target.name)
  }
  return `https://uri.amap.com/marker?${params.toString()}`
}

/** 是否移动端（含 iPad，它也有高德 App）。 */
export function isMobile(): boolean {
  if (typeof navigator === 'undefined') {
    return false
  }
  const ua = navigator.userAgent
  return /Android|iPhone|iPad|iPod|HarmonyOS|Mobile/i.test(ua)
}

/**
 * 拉起高德：移动端先试唤起 App，失败就落到网页版。
 *
 * 判断「有没有唤起成功」的办法是看页面有没有被切到后台 —— 唤起 App 会让当前页
 * 触发 visibilitychange 并进入 hidden；没唤起则一直可见。超时后走网页版。
 *
 * 不用 navigator.geolocation 定位再跳：高德 App 内置浏览器等环境会直接拒掉
 * 定位权限，链路会断在这里。经纬度让用户录入时填好更可靠。
 */
export function openAmap(target: AmapTarget, options?: { timeout?: number }): void {
  if (typeof window === 'undefined') {
    return
  }
  const timeout = options?.timeout ?? 1200

  // PC 端没什么可唤起的，直接开网页版
  if (!isMobile()) {
    window.open(buildAmapUrl(target, false), '_blank', 'noopener,noreferrer')
    return
  }

  const appUrl = buildAmapUrl(target, true)
  const webUrl = buildAmapUrl(target, false)
  let escaped = false

  const onVisibilityChange = () => {
    if (document.visibilityState === 'hidden') {
      escaped = true
    }
  }
  document.addEventListener('visibilitychange', onVisibilityChange)

  // 用 location.href 而不是 window.open：唤起 App 需要留在当前导航上下文里，
  // 新开窗口的方案在 iOS Safari 上会被拦。
  window.location.href = appUrl

  window.setTimeout(() => {
    document.removeEventListener('visibilitychange', onVisibilityChange)
    if (escaped) {
      return
    }
    // 没能唤起（没装 App / 被系统拦），退到网页版，保证一定看得到地图
    window.location.href = webUrl
  }, timeout)
}
