/**
 * 技术指标计算 —— MACD / KDJ / RSI / WR / 均线排列 / 交叉信号。
 *
 * 与 `utils/stockIndicators.ts` 的分工：那里是「怎么算」（纯数学，口径唯一），
 * 这里是「算出来叫什么」（判读：金叉/死叉、超买/超卖、多头/空头排列）。
 * 判读规则会随口径调整而变化，单独放一层才好改、好测。
 */
import { emaFilled, findCross, sma, type CrossSignal } from './stockIndicators.ts'

export interface OhlcBar {
  high: number
  low: number
  close: number
}

export interface MacdPoint {
  dif: number
  dea: number
  macd: number
}

/** MACD：不足 26 根返回空数组（不硬凑出半真半假的值） */
export function calcMACD(closes: readonly number[]): MacdPoint[] {
  if (!Array.isArray(closes) || closes.length < 26) return []
  const ema12 = emaFilled(closes, 12)
  const ema26 = emaFilled(closes, 26)
  const dif = ema12.map((v, i) => v - ema26[i])
  const dea = emaFilled(dif, 9)
  return dif.map((v, i) => ({ dif: v, dea: dea[i], macd: 2 * (v - dea[i]) }))
}

/**
 * KDJ：RSV 取 n 根窗口的最高/最低，K、D 用 2/3 + 1/3 平滑，初值 50。
 *
 * 前 n-1 根的 RSV 记作 50（中性），而不是跳过 —— 这样 K、D、J 与输入等长，
 * 下游取「最新一根」时不用再处理空位。
 */
export function calcKDJ(bars: readonly OhlcBar[], n = 9): { k: number; d: number; j: number }[] {
  if (!Array.isArray(bars) || bars.length < n) return []
  const rsv: number[] = []
  for (let i = 0; i < bars.length; i++) {
    if (i < n - 1) {
      rsv.push(50)
      continue
    }
    let high = -Infinity
    let low = Infinity
    for (let j = i - n + 1; j <= i; j++) {
      if (bars[j].high > high) high = bars[j].high
      if (bars[j].low < low) low = bars[j].low
    }
    const close = bars[i].close
    rsv.push(high === low ? 50 : ((close - low) / (high - low)) * 100)
  }
  let k = 50
  let d = 50
  return rsv.map((v) => {
    k = (2 / 3) * k + (1 / 3) * v
    d = (2 / 3) * d + (1 / 3) * k
    return { k, d, j: 3 * k - 2 * d }
  })
}

/**
 * WR（Williams %R）：值域 -100 ~ 0。
 * 高于 -20 视为超买，低于 -80 视为超卖。
 */
export function calcWR(bars: readonly OhlcBar[], n = 14): (number | null)[] {
  if (!Array.isArray(bars) || bars.length < n) return []
  const result: (number | null)[] = []
  for (let i = 0; i < bars.length; i++) {
    if (i < n - 1) {
      result.push(null)
      continue
    }
    let high = -Infinity
    let low = Infinity
    for (let j = i - n + 1; j <= i; j++) {
      if (bars[j].high > high) high = bars[j].high
      if (bars[j].low < low) low = bars[j].low
    }
    const close = bars[i].close
    result.push(high === low ? -50 : ((high - close) / (high - low)) * -100)
  }
  return result
}

/**
 * RSI，Wilder 平滑。
 *
 * 边界处理值得记一笔：全涨（avgLoss = 0）给 100，全平（两者皆 0）给 50。
 * 常见写法是 division by zero 直接 NaN 或给 0，那会让「连续涨停」显示成超卖。
 */
export function calcRSI(closes: readonly number[], period = 14): number[] {
  if (!Array.isArray(closes) || closes.length < period + 1) return []
  const gains: number[] = []
  const losses: number[] = []
  for (let i = 1; i < closes.length; i++) {
    const diff = closes[i] - closes[i - 1]
    gains.push(diff > 0 ? diff : 0)
    losses.push(diff < 0 ? -diff : 0)
  }
  let avgGain = gains.slice(0, period).reduce((a, b) => a + b, 0) / period
  let avgLoss = losses.slice(0, period).reduce((a, b) => a + b, 0) / period
  const rsiOf = (gain: number, loss: number) =>
    loss === 0 ? (gain === 0 ? 50 : 100) : 100 - 100 / (1 + gain / loss)
  const out = [rsiOf(avgGain, avgLoss)]
  for (let i = period; i < gains.length; i++) {
    avgGain = (avgGain * (period - 1) + gains[i]) / period
    avgLoss = (avgLoss * (period - 1) + losses[i]) / period
    out.push(rsiOf(avgGain, avgLoss))
  }
  return out
}

/** 均线最新值：只返回数据够用的周期 */
export function latestMAs(closes: readonly number[]): { period: number; value: number }[] {
  const periods = [5, 10, 20, 30]
  if (!Array.isArray(closes) || closes.length < 5) return []
  const out: { period: number; value: number }[] = []
  for (const p of periods) {
    if (closes.length < p) continue
    let sum = 0
    for (let i = closes.length - p; i < closes.length; i++) sum += closes[i]
    out.push({ period: p, value: sum / p })
  }
  return out
}

export type MaAlignment = 'bullish' | 'bearish' | 'tangled'

/**
 * 均线排列：短周期均线全部在上为多头排列，全部在下为空头排列，其余为交叉缠绕。
 *
 * 至少要 3 条均线才判 —— 两条当然一条在上一条在下，永远只有多/空两种结果，
 * 看不出「正在收敛」这个真正有用的信息。
 */
export function judgeAlignment(mas: readonly { period: number; value: number }[]): MaAlignment | null {
  if (!Array.isArray(mas) || mas.length < 3) return null
  const sorted = [...mas].sort((a, b) => a.period - b.period)
  let bullish = true
  let bearish = true
  for (let i = 0; i < sorted.length - 1; i++) {
    if (sorted[i].value <= sorted[i + 1].value) bullish = false
    if (sorted[i].value >= sorted[i + 1].value) bearish = false
  }
  if (bullish) return 'bullish'
  if (bearish) return 'bearish'
  return 'tangled'
}

export type KdjStatus = 'overbought' | 'oversold' | 'normal'

/** KDJ 超买超卖：K > 80 或 D > 80 或 J > 100 为超买，对称下界为超卖 */
export function judgeKdj(k: number, d: number, j: number): KdjStatus {
  if (k > 80 || d > 80 || j > 100) return 'overbought'
  if (k < 20 || d < 20 || j < 0) return 'oversold'
  return 'normal'
}

/** RSI 判读 */
export function judgeRsi(v: number): KdjStatus {
  if (v > 70) return 'overbought'
  if (v < 30) return 'oversold'
  return 'normal'
}

/** WR 判读（注意量纲：WR 是 -100 ~ 0，越接近 0 越超买） */
export function judgeWr(v: number): KdjStatus {
  if (v >= -20) return 'overbought'
  if (v <= -80) return 'oversold'
  return 'normal'
}

/**
 * 均线趋势：最短周期均值高于最长周期均值即视为多头。
 * 与 `judgeAlignment` 的区别：这里只看首尾，允许中间交叉。
 */
export function judgeMaTrend(mas: readonly { period: number; value: number }[]): 'bull' | 'bear' | null {
  if (!Array.isArray(mas) || mas.length < 2) return null
  const sorted = [...mas].sort((a, b) => a.period - b.period)
  return sorted[0].value > sorted[sorted.length - 1].value ? 'bull' : 'bear'
}

/** MACD 与 KDJ 的交叉信号（最近 2 根内） */
export function macdCross(points: readonly MacdPoint[]): CrossSignal | null {
  return findCross(points, 'dif', 'dea', 2)
}

export function kdjCross(points: readonly { k: number; d: number; j: number }[]): CrossSignal | null {
  return findCross(points, 'k', 'd', 2)
}

export interface TechAnalysis {
  macd: MacdPoint
  macdCross: CrossSignal | null
  kdj: { k: number; d: number; j: number }
  kdjCross: CrossSignal | null
  kdjStatus: KdjStatus
  rsi: number | null
  rsiStatus: KdjStatus | null
  wr: number | null
  wrStatus: KdjStatus | null
  mas: { period: number; value: number }[]
  maTrend: 'bull' | 'bear' | null
  alignment: MaAlignment | null
  /** 参与计算的有效 K 线根数，用于在界面上说明口径 */
  bars: number
}

/**
 * 一次性算出弹窗要展示的全部指标。
 *
 * 返回 null 的语义是「数据不够，算不出可信结论」，而不是「算出来是 0」——
 * 界面据此显示「K 线不足」，比展示一个看起来精确但意义不明的数字诚实。
 */
export function analyzeTech(bars: readonly OhlcBar[]): TechAnalysis | null {
  if (!Array.isArray(bars) || bars.length < 26) return null
  const closes = bars.map((b) => b.close)

  const macdPoints = calcMACD(closes)
  const latestMacd = macdPoints.length > 0 ? macdPoints[macdPoints.length - 1] : null
  const kdjPoints = calcKDJ(bars)
  const latestKdj = kdjPoints.length > 0 ? kdjPoints[kdjPoints.length - 1] : null
  if (!latestMacd || !latestKdj) return null

  const rsiValues = calcRSI(closes)
  const rsi = rsiValues.length > 0 ? rsiValues[rsiValues.length - 1] : null

  const wrValues = calcWR(bars)
  const wr = wrValues.length > 0 ? (wrValues[wrValues.length - 1] as number | null) : null

  const mas = latestMAs(closes)

  return {
    macd: latestMacd,
    macdCross: macdCross(macdPoints),
    kdj: latestKdj,
    kdjCross: kdjCross(kdjPoints),
    kdjStatus: judgeKdj(latestKdj.k, latestKdj.d, latestKdj.j),
    rsi,
    rsiStatus: rsi === null ? null : judgeRsi(rsi),
    wr,
    wrStatus: wr === null ? null : judgeWr(wr),
    mas,
    maTrend: judgeMaTrend(mas),
    alignment: judgeAlignment(mas),
    bars: bars.length,
  }
}

/** 供测试与回测使用：导出 SMA 的薄封装，避免各处重复手写窗口求和 */
export function movingAverage(values: readonly number[], period: number): (number | null)[] {
  return sma(values, period)
}
