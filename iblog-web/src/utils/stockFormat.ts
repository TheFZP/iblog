/**
 * 图表配色与主题常量。
 *
 * <p>lightweight-charts 是 canvas 渲染，拿不到 CSS 变量，必须在 JS 里给具体色值。
 * 这里的值必须与 {@code styles/index.css} 的令牌手工保持一致 ——
 * 改设计令牌时记得同步改这里，否则深色模式下图表会是浅色底。</p>
 */

/** A 股口径：红涨绿跌（与 views/stock/index.vue 的 .tone-up/.tone-down 一致） */
export const UP_COLOR = { light: '#d93025', dark: '#ff6b5e' }
export const DOWN_COLOR = { light: '#14803c', dark: '#45c26f' }

export interface ChartTheme {
  text: string
  grid: string
  border: string
  /** 成交量柱（半透明） */
  upVolume: string
  downVolume: string
  /** 分时均价线 */
  avgLine: string
  /** 参考线（昨收/成本价） */
  refLine: string
}

export function chartTheme(dark: boolean): ChartTheme {
  return dark
    ? {
        text: '#8b9399',
        grid: 'rgba(255, 255, 255, 0.06)',
        border: 'rgba(255, 255, 255, 0.12)',
        upVolume: 'rgba(255, 107, 94, 0.45)',
        downVolume: 'rgba(69, 194, 111, 0.45)',
        avgLine: '#e8b339',
        refLine: '#7d868c'
      }
    : {
        text: '#777b86',
        grid: 'rgba(163, 166, 175, 0.16)',
        border: 'rgba(163, 166, 175, 0.28)',
        upVolume: 'rgba(217, 48, 37, 0.4)',
        downVolume: 'rgba(20, 128, 60, 0.4)',
        avgLine: '#d99b1e',
        refLine: '#a3a6af'
      }
}

/** 当前是否深色模式（与本项目的 :root[data-theme='dark'] 一致） */
export function isDarkMode(): boolean {
  if (typeof document === 'undefined') {
    return false
  }
  return document.documentElement.dataset.theme === 'dark'
}

/** 所有 K 线周期 */
export const KLINE_PERIODS = [
  { value: 'm5', label: '5分' },
  { value: 'm15', label: '15分' },
  { value: 'm30', label: '30分' },
  { value: 'm60', label: '60分' },
  { value: 'day', label: '日K' },
  { value: 'week', label: '周K' },
  { value: 'month', label: '月K' }
] as const

/** 分钟级周期：返回的 date 是 yyyyMMddHHmm，需要转时间戳 */
export const MINUTE_PERIODS = new Set(['m5', 'm15', 'm30', 'm60'])

/**
 * 后端 K 线 date → lightweight-charts 的 time。
 *
 * <p>分钟级是 {@code yyyyMMddHHmm} 12 位数字，日/周/月是 {@code yyyy-mm-dd} 字符串。
 * 分钟级按 UTC 编码（东八区时间当 UTC 用），与分时图保持一致，这样 crosshair 显示的时间才是对的。</p>
 */
export function toChartTime(dateStr: string): string | number {
  if (typeof dateStr === 'string' && /^\d{12}$/.test(dateStr)) {
    const year = parseInt(dateStr.slice(0, 4), 10)
    const month = parseInt(dateStr.slice(4, 6), 10) - 1
    const day = parseInt(dateStr.slice(6, 8), 10)
    const h = parseInt(dateStr.slice(8, 10), 10)
    const m = parseInt(dateStr.slice(10, 12), 10)
    return Math.floor(Date.UTC(year, month, day, h, m) / 1000)
  }
  return dateStr
}

/** 数值格式化：千分位 + 保留小数 */
export function fmtNum(n: number | null | undefined, digits = 2): string {
  if (n == null || Number.isNaN(n)) {
    return '--'
  }
  return n.toLocaleString('zh-CN', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits
  })
}

/** 带符号的数值（涨跌额/涨跌幅统一用它） */
export function fmtSigned(n: number | null | undefined, digits = 2): string {
  if (n == null || Number.isNaN(n)) {
    return '--'
  }
  const s = fmtNum(Math.abs(n), digits)
  return n >= 0 ? `+${s}` : `-${s}`
}

/**
 * 成交量简写：手 → 万手 / 亿手。
 *
 * <p>注意前端拿到的 volume 单位是「手」（100 股），后端已按此归一化。</p>
 */
export function fmtVolume(hands: number | null | undefined): string {
  if (hands == null || Number.isNaN(hands)) {
    return '--'
  }
  if (hands >= 1e8) {
    return `${(hands / 1e8).toFixed(2)}亿手`
  }
  if (hands >= 1e4) {
    return `${(hands / 1e4).toFixed(2)}万手`
  }
  return `${hands.toFixed(0)}手`
}

/** 成交额简写：万元 → 亿/万 */
export function fmtTurnover(wan: number | null | undefined): string {
  if (wan == null || Number.isNaN(wan)) {
    return '--'
  }
  if (wan >= 1e4) {
    return `${(wan / 1e4).toFixed(2)}亿`
  }
  return `${wan.toFixed(0)}万`
}

/** 涨跌方向 → 全局色调类名 */
export function toneClass(n: number | null | undefined): string {
  if (n == null || Number.isNaN(n) || n === 0) {
    return 'tone-flat'
  }
  return n > 0 ? 'tone-up' : 'tone-down'
}
