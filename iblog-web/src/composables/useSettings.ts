import { computed, ref } from 'vue'

// 带 .ts 后缀是刻意的：scripts/settings.test.mjs 由 node --experimental-strip-types
// 直接 import 本文件，Node 的 ESM 解析不做「补后缀」，写 './useWatchlistNotifications' 会 ERR_MODULE_NOT_FOUND。
import {
  DEFAULT_WATCH_NOTIFY_SETTINGS,
  type WatchNotifySettings
} from './useWatchlistNotifications.ts'

/**
 * 全局设置中心的数据层。
 *
 * <p>为什么要有这一层：原来「通知开关」藏在板块里、「刷新间隔」写死在
 * `marketTime.refreshIntervalMs` 里、「图表均线」在 `useChartSettings` 里，用户在三个
 * 地方找不到同一个设置。这里把「通知」和「刷新」收进来，图表偏好仍归
 * `useChartSettings`（它已经有自己的键与单测，重复一套会造成两份 json 打架），
 * 设置面板只是把两边的读写接到同一屏。</p>
 *
 * <p>存 localStorage 不存后端：这些是「本机这台设备的偏好」——手机上不需要
 * 15 秒轮询一次指数，桌面端也不需要为省流量关掉通知。与 `usePriceAlerts`、
 * `useChartSettings` 同属本机状态。</p>
 *
 * <p>`sanitize` 以 DEFAULTS 当 schema：类型不符一律回退默认值，而不是「信任读到的值」。
 * 理由是 `quotesRefreshMs: "abc"` 会让 `setTimeout` 立刻触发，变成 0ms 忙轮询把 CPU
 * 打满，这种错误不该靠调用方每次都判空。</p>
 */

const STORAGE_KEY = 'iblog-stock-settings'

/** 刷新间隔可选项（毫秒）。0 表示不自动刷新。 */
export const REFRESH_INTERVAL_CHOICES = [
  { value: 10000, label: '10 秒' },
  { value: 15000, label: '15 秒' },
  { value: 30000, label: '30 秒' },
  { value: 60000, label: '1 分钟' },
  { value: 120000, label: '2 分钟' },
  { value: 300000, label: '5 分钟' },
  { value: 0, label: '不自动刷新' }
] as const

/**
 * 允许的刷新间隔白名单。
 *
 * <p>不做「任意数字」：用户可以输入 1 毫秒，那等于把行情接口当 DDoS 打，
 * 上游限流后整个页面都拿不到数据，坏处远大于「可配置」的收益。</p>
 */
const ALLOWED_REFRESH = new Set<number>(REFRESH_INTERVAL_CHOICES.map((item) => item.value))

export interface RefreshSettings {
  /** 大盘指数 */
  indicesRefreshMs: number
  /** 自选/持仓行情 */
  quotesRefreshMs: number
  /** 日 K */
  klineRefreshMs: number
  /** 分时 */
  intradayRefreshMs: number
}

export interface AppSettings extends WatchNotifySettings, RefreshSettings {
  /** 价格提醒（用户自定义价位线）总开关 */
  priceAlertsEnabled: boolean
}

export const DEFAULT_SETTINGS: AppSettings = {
  ...DEFAULT_WATCH_NOTIFY_SETTINGS,
  priceAlertsEnabled: true,
  // 默认值与改动前 marketTime.refreshIntervalMs 的实际行为一致（交易 15 秒 / 其余更长），
  // 老用户升级后不会觉得「怎么突然变快了」
  indicesRefreshMs: 60000,
  quotesRefreshMs: 15000,
  klineRefreshMs: 120000,
  intradayRefreshMs: 60000
}

/** 把任意读到的值收敛成合法设置：类型不对、不在白名单、越界一律回退默认 */
export function sanitizeSettings(raw: unknown): AppSettings {
  const source = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>
  const out: AppSettings = { ...DEFAULT_SETTINGS }

  const boolKeys: (keyof AppSettings)[] = [
    'notifyEnabled',
    'notifyUp5',
    'notifyUp7',
    'notifyLimitUp',
    'notifyDown5',
    'notifyDown7',
    'notifyLimitDown',
    'notifyFastRise',
    'notifyFastFall',
    'priceAlertsEnabled'
  ]
  for (const key of boolKeys) {
    const value = source[key]
    if (typeof value === 'boolean') {
      ;(out[key] as boolean) = value
    }
  }

  const msKeys: (keyof AppSettings)[] = [
    'indicesRefreshMs',
    'quotesRefreshMs',
    'klineRefreshMs',
    'intradayRefreshMs'
  ]
  for (const key of msKeys) {
    const value = source[key]
    if (typeof value === 'number' && ALLOWED_REFRESH.has(value)) {
      ;(out[key] as number) = value
    }
  }

  const threshold = source.notifyFastThreshold
  if (typeof threshold === 'number' && Number.isFinite(threshold)) {
    // 1~5 之外的值没有实际意义：低于 1 会被正常抖动刷屏，高于 5 就等同上一个涨停
    out.notifyFastThreshold = Math.min(5, Math.max(1, Math.round(threshold * 2) / 2))
  }

  return out
}

function load(): AppSettings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) {
      return { ...DEFAULT_SETTINGS }
    }
    return sanitizeSettings(JSON.parse(raw))
  } catch {
    // 隐私模式 / 脏数据：静默回默认，读不出设置不该让页面白屏
    return { ...DEFAULT_SETTINGS }
  }
}

const settings = ref<AppSettings>(load())

function persist(next: AppSettings) {
  settings.value = next
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
  } catch {
    /* 配额满或隐私模式：内存里已生效，只是刷新后丢失 */
  }
}

/**
 * 全局设置。
 *
 * <p>单例：设置面板改、巡查器读，必须是同一份 ref，否则改完要刷新页面才生效。</p>
 */
export function useSettings() {
  return {
    settings,
    /** 通知类设置：直接喂给 useWatchlistNotifications().checkAndNotify */
    notifySettings: computed<WatchNotifySettings>(() => ({
      notifyEnabled: settings.value.notifyEnabled,
      notifyUp5: settings.value.notifyUp5,
      notifyUp7: settings.value.notifyUp7,
      notifyLimitUp: settings.value.notifyLimitUp,
      notifyDown5: settings.value.notifyDown5,
      notifyDown7: settings.value.notifyDown7,
      notifyLimitDown: settings.value.notifyLimitDown,
      notifyFastRise: settings.value.notifyFastRise,
      notifyFastFall: settings.value.notifyFastFall,
      notifyFastThreshold: settings.value.notifyFastThreshold
    })),
    refreshSettings: computed<RefreshSettings>(() => ({
      indicesRefreshMs: settings.value.indicesRefreshMs,
      quotesRefreshMs: settings.value.quotesRefreshMs,
      klineRefreshMs: settings.value.klineRefreshMs,
      intradayRefreshMs: settings.value.intradayRefreshMs
    })),
    /** 只改一个字段；每次都过一遍 sanitize，避免 UI 传进来越界值 */
    update<K extends keyof AppSettings>(key: K, value: AppSettings[K]) {
      persist(sanitizeSettings({ ...settings.value, [key]: value }))
    },
    /** 批量替换（设置面板的「恢复默认」） */
    replace(next: Partial<AppSettings>) {
      persist(sanitizeSettings({ ...settings.value, ...next }))
    },
    reset() {
      persist({ ...DEFAULT_SETTINGS })
    }
  }
}

/** 设置里是否有任何「需要开通知」的开关被打开（巡查器据此决定要不要启动） */
export function hasAnyNotifyEnabled(current: AppSettings): boolean {
  return (
    current.notifyEnabled &&
    (current.notifyUp5 ||
      current.notifyUp7 ||
      current.notifyLimitUp ||
      current.notifyDown5 ||
      current.notifyDown7 ||
      current.notifyLimitDown ||
      current.notifyFastRise ||
      current.notifyFastFall)
  )
}
