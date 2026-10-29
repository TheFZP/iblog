import { computed, ref } from 'vue'

/**
 * K 线图的显示偏好（均线周期、30 日高低参考线）。
 *
 * <p>为什么放 localStorage 而不是跟后端 {@code stock_setting} 走：这是纯视觉偏好，
 * 每台设备的屏幕宽度不同、看的周期习惯也不同（手机上看 250 日均线没有意义），
 * 强同步反而会把桌面端的设置带到手机上。与 {@code usePriceAlerts} 同属「本机状态」。</p>
 *
 * <p>单例模式：多个组件（详情页、技术分析页）读同一份 ref，一处改动全部刷新，
 * 不需要事件总线。</p>
 */

/** 可选的均线周期，与桌面版 stock-analysis-prh 的设置面板保持一致。 */
export const MA_PERIOD_CHOICES = [5, 10, 20, 30, 60, 120, 250] as const

/** 每根均线的颜色。颜色与周期绑定而不是与顺序绑定，勾掉 MA20 时 MA30 不会跳色。 */
export const MA_COLOR_MAP: Record<number, string> = {
  5: '#f0b429',
  10: '#4a90d9',
  20: '#b56bd9',
  30: '#d96ba0',
  60: '#5fbf8f',
  120: '#4fb8c0',
  250: '#8a8f98'
}

/** 默认只开四条：再多的均线在日线图上会糊成一片。 */
const DEFAULT_MA_PERIODS = [5, 10, 20, 60]

const STORAGE_KEY = 'iblog-stock-chart-settings'

export interface ChartSettings {
  /** 要显示的均线周期，升序，最多 6 条（超出时图表看不清） */
  maPeriods: number[]
  /** 是否在 K 线图上画近 30 日最高/最低价参考线 */
  show30DayHL: boolean
}

/** 最多同时显示的均线数量。上限不是随意定的：再多 legend 会折行挤占画布高度。 */
export const MAX_MA_PERIODS = 6

function load(): ChartSettings {
  const fallback: ChartSettings = { maPeriods: [...DEFAULT_MA_PERIODS], show30DayHL: false }
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) {
      return fallback
    }
    const parsed = JSON.parse(raw) as Partial<ChartSettings> | null
    if (!parsed || typeof parsed !== 'object') {
      return fallback
    }
    const periods = sanitizePeriods(parsed.maPeriods)
    return {
      // 用户可能把所有均线都关掉（只想看裸 K），所以空数组是合法值，不能回退成默认
      maPeriods: periods,
      show30DayHL: parsed.show30DayHL === true
    }
  } catch {
    // 隐私模式或脏数据：静默用默认值，配置读不出来不该让页面白屏
    return fallback
  }
}

/** 只保留白名单里的周期、去重、升序、截断到上限。 */
export function sanitizePeriods(input: unknown): number[] {
  if (!Array.isArray(input)) {
    return []
  }
  const allowed = new Set<number>(MA_PERIOD_CHOICES)
  const unique = new Set<number>()
  for (const item of input) {
    const value = typeof item === 'number' ? item : Number(item)
    if (Number.isInteger(value) && allowed.has(value)) {
      unique.add(value)
    }
  }
  return [...unique].sort((a, b) => a - b).slice(0, MAX_MA_PERIODS)
}

const settings = ref<ChartSettings>(load())

function persist(next: ChartSettings) {
  settings.value = next
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
  } catch {
    // 配额满或隐私模式：内存里已生效，只是刷新后丢失，不值得打断用户
  }
}

export function useChartSettings() {
  return {
    settings,
    /** 当前要画的均线周期，图表直接吃这个 */
    maPeriods: computed(() => settings.value.maPeriods),
    show30DayHL: computed(() => settings.value.show30DayHL),
    /** 切换某条均线的显示/隐藏。超上限时忽略新增，避免 legend 把画布挤没 */
    toggleMaPeriod(period: number) {
      if (!(MA_PERIOD_CHOICES as readonly number[]).includes(period)) {
        return
      }
      const current = settings.value.maPeriods
      const exists = current.includes(period)
      if (!exists && current.length >= MAX_MA_PERIODS) {
        return
      }
      const next = exists ? current.filter((p) => p !== period) : [...current, period]
      persist({ ...settings.value, maPeriods: sanitizePeriods(next) })
    },
    setShow30DayHL(value: boolean) {
      persist({ ...settings.value, show30DayHL: value === true })
    },
    /** 恢复默认，给设置面板的「恢复默认」用 */
    resetChartSettings() {
      persist({ maPeriods: [...DEFAULT_MA_PERIODS], show30DayHL: false })
    }
  }
}
