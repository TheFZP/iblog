/**
 * 筹码分布（成本分布）计算。
 *
 * <p>思路：把每根 K 线的成交量按「三角形分布」摊到它自己的价格区间上
 * （收盘价附近权重最高，往两端递减），逐根累积成一张价格-筹码图。
 * 筹码峰就是图上最高的那一档，COST5/50/95 是累积占比达到 5%/50%/95% 的价格。</p>
 *
 * <h3>为什么要换手率衰减</h3>
 * <p>不做衰减等于假设「两年前买的人今天还拿着」，算出来的筹码峰会一直粘在
 * 很久以前的成本区，对当下毫无参考价值。真实筹码会被后续成交不断置换，
 * 所以每过一根 K 线，历史筹码按该根的换手率折损。换手率只有最新一根是已知的
 * （来自行情快照），其余各根按「量 / 最新一根的量」等比推算 —— 这个比值在任意
 * 量纲下都成立（分子分母同量纲自动约去），所以日 K 用「手」也用得。</p>
 *
 * <p>只有日 K 能带换手率：分钟/周/月 K 的「最后一根的量」不是「当日成交量」，
 * 拿它反推流通股本会算错衰减，所以那些周期传 undefined，面板上要标注未衰减。</p>
 */

/** 统计窗口上限（根）：日 K 约两年。窗口再长衰减也把早期筹码抹平了，白算 */
export const CHIP_MAX_BARS = 480

/** 默认价格分段数 */
const DEFAULT_STEPS = 60

export interface ChipBar {
  high: number
  low: number
  close: number
  volume: number
}

export interface ChipDistributionOptions {
  /** 价格分段数，默认 60（会按价格区间自动收紧，保证每档 ≥ 0.01 元） */
  steps?: number
  /** 平滑窗口，默认 3 */
  smoothWindow?: number
  /** 最新一根（日 K）的换手率 %，提供则启用换手率衰减 */
  turnoverRate?: number
  /** 衰减系数，默认 1（完全按换手率置换） */
  decayCoefficient?: number
}

export interface ChipPoint {
  price: number
  /** 该价位筹码占总量的比例（0~1） */
  ratio: number
}

export interface ChipCostLevels {
  COST5?: number
  COST15?: number
  COST50?: number
  COST85?: number
  COST95?: number
}

export interface ChipDistribution {
  distribution: ChipPoint[]
  /** 筹码峰价格 */
  peakPrice: number
  /** 峰值占比 */
  peakRatio: number
  currentPrice: number
  costLevels: ChipCostLevels
  /** 加权平均成本 */
  avgCost: number
  /** 是否应用了换手率衰减 —— 面板必须据此标注口径 */
  decayed: boolean
  /** 衰减所用的最新换手率（%），未衰减为 null */
  decayTurnoverRate: number | null
}

/**
 * 从 K 线算筹码分布。
 *
 * <p>返回 null 的语义是「数据不够/价格区间退化，算不出可信结果」，
 * 而不是「算出来是 0」—— 面板据此显示「数据不足」而不是画一张空图。
 */
export function calcChipDistribution(
  klineData: readonly ChipBar[],
  options: ChipDistributionOptions = {}
): ChipDistribution | null {
  const src = Array.isArray(klineData) ? klineData.filter((k) => k && Number.isFinite(k.close)) : []
  if (src.length < 5) return null

  // steps 要防 0 / 负数 / NaN：否则 priceStep 变 Infinity，整张表全是 NaN
  const stepsRaw = Math.max(1, Math.min(500, Math.floor(Number(options.steps) || DEFAULT_STEPS)))
  const smoothWindow = Math.max(1, Math.floor(Number(options.smoothWindow) || 3))

  const data = src.slice(-CHIP_MAX_BARS)

  // ---- 换手率衰减参数 ----
  const turnoverPct = Number(options.turnoverRate)
  const coeffRaw = options.decayCoefficient
  const decayCoefficient = coeffRaw == null ? 1 : Number(coeffRaw)
  const latestVolume = Number(data[data.length - 1]?.volume) || 0
  const decayed =
    Number.isFinite(turnoverPct) &&
    turnoverPct > 0 &&
    latestVolume > 0 &&
    Number.isFinite(decayCoefficient) &&
    decayCoefficient > 0

  let minPrice = Infinity
  let maxPrice = -Infinity
  for (const k of data) {
    if (k.low < minPrice) minPrice = k.low
    if (k.high > maxPrice) maxPrice = k.high
  }
  if (!(maxPrice - minPrice > 0.001)) return null

  // 上下各留 5% 边距，避免最高/最低那根被压到首尾档位的边界上
  const padding = (maxPrice - minPrice) * 0.05
  minPrice -= padding
  maxPrice += padding

  // 窄幅区间（低价股/分钟 K）下固定分段会得到 priceStep < 0.01，四舍五入后
  // 相邻档位价格标签重复、「峰」标记同时命中多行。按区间收紧档位数，保证每档 ≥ 0.01 元。
  const steps = Math.max(1, Math.min(stepsRaw, Math.floor((maxPrice - minPrice) / 0.01)))
  const priceStep = (maxPrice - minPrice) / steps
  const distribution = new Float64Array(steps)

  for (const k of data) {
    const { high, low, close } = k
    const volume = Number(k.volume) || 0

    // 先按本根换手率折损已沉淀的历史筹码，再叠加本根新增筹码
    if (decayed) {
      const tr = Math.min(1, Math.max(0, (turnoverPct / 100) * (volume / latestVolume)))
      const keep = Math.max(0, 1 - decayCoefficient * tr)
      for (let i = 0; i < steps; i++) distribution[i] *= keep
    }

    if (volume <= 0) continue

    const lowIdx = Math.max(0, Math.min(steps - 1, Math.floor((low - minPrice) / priceStep)))
    const highIdx = Math.max(0, Math.min(steps - 1, Math.floor((high - minPrice) / priceStep)))
    const closeIdx = Math.max(0, Math.min(steps - 1, Math.floor((close - minPrice) / priceStep)))

    // 用金额（量 × 收盘价）比用「手数」更能反映资金沉淀；口径对所有 K 线一致
    const allocValue = volume * close

    const range = highIdx - lowIdx
    // 一字板（high === low）或整段价格落在同一档：全部计入该档，不能丢
    if (range === 0) {
      distribution[closeIdx] += allocValue
      continue
    }

    // 三角形分布：收盘价附近权重最高，向两端递减（最低留 0.1，长实体也不至于被摊成平的）
    const halfRange = Math.max(1, range / 2)
    const weights: number[] = []
    let totalWeight = 0
    for (let i = lowIdx; i <= highIdx; i++) {
      const dist = Math.abs(i - closeIdx) / halfRange
      const weight = Math.max(0.1, 1 - dist * 0.45)
      weights.push(weight)
      totalWeight += weight
    }
    for (let i = 0; i < weights.length; i++) {
      distribution[lowIdx + i] += (weights[i] / totalWeight) * allocValue
    }
  }

  let total = 0
  for (let i = 0; i < steps; i++) total += distribution[i]
  if (!(total > 0)) return null

  const raw: ChipPoint[] = Array.from(distribution, (v, i) => ({
    price: Number((minPrice + i * priceStep).toFixed(2)),
    ratio: v / total
  }))

  // 平滑：相邻档位单看会锯齿，峰的位置跳来跳去
  const half = Math.floor(smoothWindow / 2)
  const smoothed: ChipPoint[] = raw.map((item, i) => {
    let sum = 0
    let count = 0
    for (let j = i - half; j <= i + half; j++) {
      if (j >= 0 && j < raw.length) {
        sum += raw[j].ratio
        count++
      }
    }
    return { price: item.price, ratio: count > 0 ? sum / count : item.ratio }
  })

  let maxRatio = 0
  let peakIdx = 0
  for (let i = 0; i < smoothed.length; i++) {
    if (smoothed[i].ratio > maxRatio) {
      maxRatio = smoothed[i].ratio
      peakIdx = i
    }
  }

  // COST 分位：按价格升序累积占比，跨过 5%/15%/50%/85%/95% 的那一档就是该分位成本
  const sorted = [...smoothed].sort((a, b) => a.price - b.price)
  const costLevels: ChipCostLevels = {}
  const targets = [5, 15, 50, 85, 95]
  let cumulative = 0
  let targetIdx = 0
  for (const item of sorted) {
    cumulative += item.ratio
    while (targetIdx < targets.length && cumulative >= targets[targetIdx] / 100) {
      costLevels[`COST${targets[targetIdx]}` as keyof ChipCostLevels] = item.price
      targetIdx++
    }
  }

  let weightedSum = 0
  let weightTotal = 0
  for (const item of smoothed) {
    weightedSum += item.price * item.ratio
    weightTotal += item.ratio
  }

  return {
    distribution: smoothed,
    peakPrice: smoothed[peakIdx].price,
    peakRatio: maxRatio,
    currentPrice: Number(data[data.length - 1].close),
    costLevels,
    avgCost: Number((weightTotal > 0 ? weightedSum / weightTotal : 0).toFixed(2)),
    decayed,
    decayTurnoverRate: decayed ? Number(turnoverPct.toFixed(2)) : null
  }
}

/**
 * 从行情快照构造选项：只有日 K 的「最新一根成交量」与换手率同口径。
 *
 * @param turnoverRate 行情里的换手率（%）
 * @param period 当前 K 线周期
 */
export function chipOptionsFromQuote(
  turnoverRate: number | null | undefined,
  period: string
): ChipDistributionOptions {
  if (period !== 'day') return {}
  const rate = Number(turnoverRate)
  if (!Number.isFinite(rate) || rate <= 0) return {}
  return { turnoverRate: rate }
}
