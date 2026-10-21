/**
 * 技术指标基础函数 —— 单一实现，供技术分析 / T+0 信号 / 回测共用。
 *
 * 为什么集中：先前几处各写一份 EMA，且都以 `data[0]` 作为种子，
 * 导致首值权重异常、没有预热期，MACD 背离与均线判断整体偏移。
 *
 * 口径约定：
 * - `sma` / `ema` 返回与输入等长的数组，未进入有效区间的位置为 `null`（不做假值填充）
 * - `ema` 以前 `period` 根的 SMA 作为种子，第 `period - 1` 位为第一个有效值
 * - `emaFilled` 是补全版本：有效区之前用「到该位为止的简单均值」填充，
 *   这样下游可以放心做减法而不用到处判 null
 */

/** 简单移动平均；不足 period 根时为 null */
export function sma(values: readonly number[], period: number): (number | null)[] {
  const src = Array.isArray(values) ? values : []
  const out: (number | null)[] = new Array(src.length).fill(null)
  if (!(period > 0)) return out
  // 先整体校验再算：中途遇到 NaN 才退出去的话，返回的是一个「前半截有值、
  // 后半截全 null」的数组 —— 那种半成品最难排查，下游会默默按前半截下结论。
  for (let i = 0; i < src.length; i++) {
    if (!Number.isFinite(Number(src[i]))) return new Array(src.length).fill(null)
  }
  let sum = 0
  for (let i = 0; i < src.length; i++) {
    const v = Number(src[i])
    sum += v
    if (i >= period) sum -= Number(src[i - period])
    if (i >= period - 1) out[i] = sum / period
  }
  return out
}

/** 指数移动平均；以 SMA(period) 为种子，之前为 null */
export function ema(values: readonly number[], period: number): (number | null)[] {
  const src = Array.isArray(values) ? values : []
  const out: (number | null)[] = new Array(src.length).fill(null)
  if (!(period > 0) || src.length < period) return out
  const seed = sma(src.slice(0, period), period)[period - 1]
  if (seed === null) return out
  const k = 2 / (period + 1)
  out[period - 1] = seed
  for (let i = period; i < src.length; i++) {
    const v = Number(src[i])
    if (!Number.isFinite(v)) break
    out[i] = v * k + (out[i - 1] as number) * (1 - k)
  }
  return out
}

/**
 * 全长度数值版 EMA：有效区之前用逐段扩大的简单均值填充。
 * @returns 与输入等长，元素均为数字
 */
export function emaFilled(values: readonly number[], period: number): number[] {
  const src = Array.isArray(values) ? values : []
  const full = ema(src, period)
  const out = new Array<number>(src.length).fill(0)
  let sum = 0
  for (let i = 0; i < src.length; i++) {
    const v = Number(src[i])
    const safe = Number.isFinite(v) ? v : 0
    sum += safe
    out[i] = full[i] !== null ? (full[i] as number) : sum / (i + 1)
  }
  return out
}

export interface MacdResult {
  dif: number[]
  dea: number[]
  hist: number[]
  /** 此下标之前是预热态，不能用于信号判定 */
  validFrom: number
}

/** MACD（12 / 26 / 9） */
export function macd(
  closes: readonly number[],
  fast = 12,
  slow = 26,
  signal = 9,
): MacdResult {
  const src = Array.isArray(closes) ? closes : []
  const emaFast = emaFilled(src, fast)
  const emaSlow = emaFilled(src, slow)
  const dif = src.map((_, i) => emaFast[i] - emaSlow[i])
  const dea = emaFilled(dif, signal)
  const hist = dif.map((v, i) => (v - dea[i]) * 2)
  return { dif, dea, hist, validFrom: Math.max(fast, slow) + signal - 2 }
}

/** 交叉方向 */
export type CrossDirection = 'golden' | 'death'

export interface CrossSignal {
  direction: CrossDirection
  /** 距离最新一根 K 线多少根之前发生；1 表示就发生在最后一根上 */
  barsAgo: number
}

/**
 * 在 arr 末尾 lookback 根内找 key1 与 key2 的最近一次交叉。
 *
 * 返回 `barsAgo` 而不是直接拼好中文串：调用方要按「发生在几根前」决定
 * 文案强弱（刚交叉和 3 根前交叉不该说成同一句话），拼串的活儿留给展示层。
 */
export function findCross<T extends object>(
  arr: readonly (T | null | undefined)[],
  key1: keyof T,
  key2: keyof T,
  lookback = 3,
): CrossSignal | null {
  if (!Array.isArray(arr) || arr.length < lookback + 1) return null
  for (let back = 1; back <= lookback; back++) {
    const prev = arr[arr.length - back - 1]
    const curr = arr[arr.length - back]
    if (!prev || !curr) continue
    const prevDiff = Number(prev[key1]) - Number(prev[key2])
    const currDiff = Number(curr[key1]) - Number(curr[key2])
    if (!Number.isFinite(prevDiff) || !Number.isFinite(currDiff)) continue
    if (prevDiff <= 0 && currDiff > 0) return { direction: 'golden', barsAgo: back }
    if (prevDiff >= 0 && currDiff < 0) return { direction: 'death', barsAgo: back }
  }
  return null
}
