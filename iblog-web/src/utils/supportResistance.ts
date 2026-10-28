/**
 * 支撑 / 阻力位计算（纯函数，无 Vue 依赖）。
 *
 * <p>做法：先找波段高低点（左右各 3 根内的极值），把相近价格聚类成「一条线」，
 * 只保留当前价上下各 25% 区间内的前 3 条；不足 3 条时用斐波那契回撤位补足。</p>
 *
 * <p>聚类不能省：一段震荡里同一个价位会被反复触及，不聚类就会得到 20 条
 * 几乎重合的阻力位，图上糊成一片、也没法排序。</p>
 */

export interface Candle {
  high: number
  low: number
  close: number
}

export interface PriceLevel {
  price: number
  /** 该价位被触及的次数（聚类内的点数）；斐波那契补充的为 0 */
  strength: number
  /** 斐波那契比例（仅补充位有值） */
  fib?: number
}

export interface SupportResistance {
  support: PriceLevel[]
  resistance: PriceLevel[]
}

/** 波段极值的左右窗口根数 */
const LOOKBACK = 3
/** 聚类阈值：相对均价 0.5% 以内视为同一价位 */
const CLUSTER_THRESHOLD = 0.005
/** 只关心当前价 ±25% 的区间 */
const PRICE_RANGE_RATIO = 0.25
/** 斐波那契回撤比例 */
const FIB_RATIOS = [0.236, 0.382, 0.5, 0.618, 0.786]
/** 每侧最多保留几条 */
const MAX_LEVELS = 3

/** 聚类合并相近价格，按被触及次数降序 */
function cluster(prices: number[], thresholdRatio = CLUSTER_THRESHOLD): PriceLevel[] {
  if (prices.length === 0) return []
  const sorted = [...prices].sort((a, b) => a - b)
  const groups: number[][] = [[sorted[0]]]
  for (let i = 1; i < sorted.length; i++) {
    const last = groups[groups.length - 1]
    const avg = last.reduce((s, v) => s + v, 0) / last.length
    if (avg > 0 && Math.abs(sorted[i] - avg) / avg <= thresholdRatio) {
      last.push(sorted[i])
    } else {
      groups.push([sorted[i]])
    }
  }
  return groups
    .map((g) => ({ price: g.reduce((s, v) => s + v, 0) / g.length, strength: g.length }))
    .sort((a, b) => b.strength - a.strength)
}

/**
 * 计算支撑与阻力位。
 *
 * @param candleData K 线（按日期升序），少于 30 根直接返回空 —— 样本太短找出来的
 *                   「波段高低点」只是噪声，标在图上会误导人。
 */
export function calcSupportResistance(candleData: readonly Candle[]): SupportResistance {
  const list = Array.isArray(candleData) ? candleData.filter((c) => c && Number.isFinite(c.high)) : []
  if (list.length < 30) return { support: [], resistance: [] }

  const latestPrice = Number(list[list.length - 1].close) || 0
  if (!(latestPrice > 0)) return { support: [], resistance: [] }

  const swingHighs: number[] = []
  const swingLows: number[] = []
  for (let i = LOOKBACK; i < list.length - LOOKBACK; i++) {
    let maxHigh = -Infinity
    let minLow = Infinity
    for (let j = i - LOOKBACK; j <= i + LOOKBACK; j++) {
      if (list[j].high > maxHigh) maxHigh = list[j].high
      if (list[j].low < minLow) minLow = list[j].low
    }
    if (list[i].high === maxHigh) swingHighs.push(list[i].high)
    if (list[i].low === minLow) swingLows.push(list[i].low)
  }

  const priceRange = latestPrice * PRICE_RANGE_RATIO

  let topResistance: PriceLevel[] = cluster(swingHighs)
    .filter((r) => r.price >= latestPrice && r.price - latestPrice <= priceRange)
    .slice(0, MAX_LEVELS)
    .sort((a, b) => a.price - b.price)

  let topSupport: PriceLevel[] = cluster(swingLows)
    .filter((s) => s.price <= latestPrice && latestPrice - s.price <= priceRange)
    .slice(0, MAX_LEVELS)
    .sort((a, b) => b.price - a.price)

  // 波段结构不够用时才拿斐波那契补，避免两种口径混着把图塞满
  if (topResistance.length + topSupport.length < MAX_LEVELS) {
    const recent = list.slice(-60)
    const fibHigh = Math.max(...recent.map((c) => c.high))
    const fibLow = Math.min(...recent.map((c) => c.low))
    const diff = fibHigh - fibLow
    if (diff > 0.001) {
      const fibLevels = FIB_RATIOS.map((r) => ({ price: fibHigh - diff * r, ratio: r }))
      const needR = Math.max(0, MAX_LEVELS - topResistance.length)
      const needS = Math.max(0, MAX_LEVELS - topSupport.length)
      if (needR > 0) {
        topResistance = [
          ...topResistance,
          ...fibLevels
            .filter((f) => f.price > latestPrice)
            .sort((a, b) => a.price - b.price)
            .slice(0, needR)
            .map((f) => ({ price: f.price, strength: 0, fib: f.ratio }))
        ]
          // 补充位可能插在已有价位之间，拼接后必须重排：顺序就是「由近到远」的语义，
          // 不重排会出现「第 2 条阻力比第 1 条还远」这种图上看着没错、读起来错位的结果
          .sort((a, b) => a.price - b.price)
          .slice(0, MAX_LEVELS)
      }
      if (needS > 0) {
        topSupport = [
          ...topSupport,
          ...fibLevels
            .filter((f) => f.price < latestPrice)
            .sort((a, b) => b.price - a.price)
            .slice(0, needS)
            .map((f) => ({ price: f.price, strength: 0, fib: f.ratio }))
        ]
          .sort((a, b) => b.price - a.price)
          .slice(0, MAX_LEVELS)
      }
    }
  }

  return { support: topSupport, resistance: topResistance }
}
