/**
 * 市场时间工具：交易时段判断。
 *
 * <p>为什么要自己做：价格提醒只在交易时段检测，否则周末/深夜浏览页面会被
 * 隔夜跳空误触发。A 股 09:30-11:30 / 13:00-15:00，港股 09:30-12:00 / 13:00-16:00。</p>
 *
 * <p>本文件不做交易日历（法定节假日）判断 —— 那需要额外拉取日历接口，
 * 网页端暂不引入。周末判断足以覆盖误报的主要场景。</p>
 */

/** 各市场交易时段（分钟数，含两端）：[上午开, 上午收] / [下午开, 下午收] */
const SESSIONS = {
  A: { morning: [570, 690], afternoon: [780, 900] },
  HK: { morning: [570, 720], afternoon: [780, 960] }
} as const

/** 港股代码是 5 位数字，其余按 A 股处理 */
function isHkCode(code?: string): boolean {
  return /^\d{5}$/.test(code ?? '')
}

/** 日期 → YYYY-MM-DD（本地时区，不能用 toISOString：东八区 08:00 前会算成前一天） */
export function getToday(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

export type MarketState = 'pre' | 'open' | 'break' | 'post' | 'holiday'

/**
 * 市场状态。
 *
 * @param code 股票代码，5 位数字按港股
 * @param at 基准时刻，默认当前（便于测试）
 */
export function getMarketState(code?: string, at: Date = new Date()): MarketState {
  const market = isHkCode(code) ? 'HK' : 'A'
  const [morningOpen, morningClose] = SESSIONS[market].morning
  const [afternoonOpen, afternoonClose] = SESSIONS[market].afternoon

  const day = at.getDay()
  if (day === 0 || day === 6) {
    return 'holiday'
  }

  const t = at.getHours() * 60 + at.getMinutes()
  if (t < morningOpen) return 'pre'
  if (t <= morningClose) return 'open'
  if (t < afternoonOpen) return 'break'
  if (t <= afternoonClose) return 'open'
  return 'post'
}

/** 是否在交易时段内（含午休，与价格提醒的轮询口径一致） */
export function isTradingHours(code?: string, at: Date = new Date()): boolean {
  const state = getMarketState(code, at)
  return state === 'open' || state === 'break'
}

/** 状态中文说明 */
export function marketStateLabel(state: MarketState): string {
  switch (state) {
    case 'pre':
      return '未开盘'
    case 'open':
      return '交易中'
    case 'break':
      return '午间休市'
    case 'post':
      return '已收盘'
    case 'holiday':
      return '休市'
  }
}

/** 裁剪历史记录，只保留最近 N 天：{ "YYYY-MM-DD": {...} } */
export function pruneHistory(history: Record<string, unknown>, days = 7): Record<string, unknown> {
  const cutoff = new Date(Date.now() - days * 86400000)
  const cutoffStr = `${cutoff.getFullYear()}-${String(cutoff.getMonth() + 1).padStart(2, '0')}-${String(cutoff.getDate()).padStart(2, '0')}`
  const cleaned: Record<string, unknown> = {}
  for (const [date, val] of Object.entries(history ?? {})) {
    if (date >= cutoffStr) {
      cleaned[date] = val
    }
  }
  return cleaned
}
