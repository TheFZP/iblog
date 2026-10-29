/**
 * 市场时间工具：交易时段判断。
 *
 * <p>为什么要自己做：价格提醒只在交易时段检测，否则周末/深夜浏览页面会被
 * 隔夜跳空误触发。A 股 09:30-11:30 / 13:00-15:00，港股 09:30-12:00 / 13:00-16:00。</p>
 *
 * <p>关于法定节假日：纯时钟判断无法知道「今天是十一长假」，会把 10 月 1 日显示成
 * 「交易中」。港股用 {@link isHKHoliday} 的静态假期表补上（见 `utils/hkHolidays.ts`，
 * 收录取到 2027 年，过期后自动降级为「只判周末」而不是判错）；A 股假期表每年由
 * 国务院临时公布，前端硬编码一定会过期，故不内置，而是靠 {@link isQuoteFresh}
 * 用上游报价自带的时间戳反证「市场其实没动」。两者组合，时钟给「现在该不该开市」，
 * 时间戳给「实际上有没有在动」。</p>
 */

// 带 .ts 后缀是刻意的：scripts/*.test.mjs 由 node --experimental-strip-types 直接
// import 本文件，Node 的 ESM 解析器不做「补后缀」推断，写 './hkHolidays' 会 ERR_MODULE_NOT_FOUND。
import { isHKHoliday } from './hkHolidays.ts'

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

export type MarketState = 'pre' | 'open' | 'break' | 'post' | 'holiday' | 'stale'

/** 任意时刻 → `YYYY-MM-DD`（本地时区，补零，与 {@link getToday} 同口径） */
function toDateStr(at: Date): string {
  return `${at.getFullYear()}-${String(at.getMonth() + 1).padStart(2, '0')}-${String(at.getDate()).padStart(2, '0')}`
}

/**
 * 市场状态。
 *
 * <p>{@code stale} 表示「时钟说是交易时段，但上游时间戳明显落后」——典型的法定节假日、
 * 或者数据源卡住。需要把上游时间戳传进 {@code quoteTime} 才能识别；不传就只能是纯时钟判断。</p>
 *
 * @param code 股票代码，5 位数字按港股
 * @param quoteTime 上游行情时间 `yyyy-MM-dd HH:mm:ss`，可选
 * @param at 基准时刻，默认当前（便于测试）
 */
export function getMarketState(
  code?: string,
  quoteTime?: string | null,
  at: Date = new Date()
): MarketState {
  const market = isHkCode(code) ? 'HK' : 'A'
  const [morningOpen, morningClose] = SESSIONS[market].morning
  const [afternoonOpen, afternoonClose] = SESSIONS[market].afternoon

  const day = at.getDay()
  if (day === 0 || day === 6) {
    return 'holiday'
  }
  // 港股还要看公众假期表：中秋、国庆这些日子是工作日但休市，光判周末会显示「交易中」。
  // 必须用 === true 判定——isHKHoliday 返回 null 表示「该年份未收录」，那是「不知道」，
  // 不能当成「不是假期」，否则表一过期就又开始把国庆当交易日。
  if (market === 'HK' && isHKHoliday(toDateStr(at)) === true) {
    return 'holiday'
  }

  const t = at.getHours() * 60 + at.getMinutes()
  const clockState: MarketState =
    t < morningOpen
      ? 'pre'
      : t <= morningClose
        ? 'open'
        : t < afternoonOpen
          ? 'break'
          : t <= afternoonClose
            ? 'open'
            : 'post'

  // 只有「时钟认为在交易」时才需要上游反证；盘前/盘后/午休本来就该是不动的
  if ((clockState === 'open' || clockState === 'break') && quoteTime && !isQuoteFresh(quoteTime, at)) {
    return 'stale'
  }
  return clockState
}

/** 上游时间戳容忍度（分钟）：行情推送本就可能有几十秒延迟，别把正常延迟当成停更 */
const FRESH_TOLERANCE_MIN = 10

/**
 * 上游行情时间戳是否「还在动」。
 *
 * @param quoteTime `yyyy-MM-dd HH:mm:ss`
 * @param at 基准时刻，默认当前
 */
export function isQuoteFresh(quoteTime?: string | null, at: Date = new Date()): boolean {
  const ts = parseQuoteTime(quoteTime)
  if (ts == null) {
    // 拿不到时间戳时不做判断 —— 宁可不报，也别把「字段缺失」说成「数据过期」
    return true
  }
  const diffMin = (at.getTime() - ts) / 60000
  return diffMin <= FRESH_TOLERANCE_MIN
}

/** 解析 `yyyy-MM-dd HH:mm:ss`（也接受带 T 的 ISO 形式）→ 毫秒；解析失败返回 null */
export function parseQuoteTime(quoteTime?: string | null): number | null {
  if (!quoteTime) return null
  const m = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?/.exec(quoteTime)
  if (!m) return null
  const [, y, mo, d, h, mi, s] = m
  // 用本地时间构造：上游给的就是交易所本地时间（东八区），不是 UTC
  const ts = new Date(+y, +mo - 1, +d, +h, +mi, s ? +s : 0).getTime()
  return Number.isNaN(ts) ? null : ts
}

/** 是否在交易时段内（含午休，与价格提醒的轮询口径一致） */
export function isTradingHours(code?: string, at: Date = new Date()): boolean {
  const state = getMarketState(code, null, at)
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
    case 'stale':
      return '休市'
  }
}

/** 状态短标签，用于行情页与详情页的状态胶囊 */
export function marketStateBadge(state: MarketState): { text: string; live: boolean } {
  switch (state) {
    case 'open':
      return { text: '交易中', live: true }
    case 'break':
      return { text: '午间休市', live: false }
    case 'pre':
      return { text: '未开盘', live: false }
    case 'post':
      return { text: '已收盘', live: false }
    case 'stale':
    case 'holiday':
      return { text: '休市', live: false }
  }
}

/**
 * 刷新间隔（毫秒）——按市场状态动态变化。
 *
 * <p>原来固定 15 秒全量刷四五个接口，收盘后、深夜、周末照样刷，等于白烧用户的流量和
 * 后端的上游配额。分三档：交易中 15 秒、午休 60 秒（仍有零星成交）、其余 5 分钟。</p>
 *
 * @param overrideMs 用户在设置里指定的「交易时段间隔」。只覆盖「交易中」这一档，
 *   午休和休市仍按倍数放大——收盘后按用户设的 10 秒去刷，除了费流量没有任何好处，
 *   这一点不该交给用户自己判断。传 0 表示不自动刷新，调用方据此停止排期。
 *   非有限数或负数视为未设置，走默认档位。
 */
export function refreshIntervalMs(state: MarketState, hidden = false, overrideMs?: number): number {
  const override = typeof overrideMs === 'number' && Number.isFinite(overrideMs) && overrideMs >= 0
    ? overrideMs
    : null

  if (override !== null) {
    // 0 = 用户明确关掉自动刷新，任何状态下都不该再排期
    if (override === 0) {
      return 0
    }
    const scaled =
      state === 'open'
        ? override
        : state === 'break'
          ? // 午休成交稀疏，但仍有零星挂单，放慢到 1/4 频率即可
            Math.max(override * 4, 30_000)
          : // 盘前/盘后/休市：价格不动，再勤也是重复拿同一份数据
            Math.max(override * 20, 120_000)
    return hidden ? scaled * 4 : scaled
  }

  let base: number
  switch (state) {
    case 'open':
      base = 15_000
      break
    case 'break':
      base = 60_000
      break
    default:
      base = 300_000
  }
  // 页面在后台时没必要按前台频率刷
  return hidden ? base * 4 : base
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
