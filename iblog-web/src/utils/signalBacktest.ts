/**
 * 日线级信号回测与胜率统计。
 *
 * <p>只回测日线规则：现有的分时信号（T+0 / 放量 / 诱多）依赖日内时间序列，
 * 而落库的历史只有日 K，拿日 K 去「回测」分时信号就是编数据。这里挑的是
 * 8 条只吃日 K 的规则，逐条统计「历史上每次触发后，持有 1/3/5/10 个交易日的结果」。</p>
 *
 * <h3>统计口径（重要，改动前先想清楚）</h3>
 * <ul>
 *   <li><b>非重叠交易</b>：持有期内不再开新仓。否则连涨一段会堆出几十笔「同一波行情」的
 *       样本，胜率虚高且毫无信息量。</li>
 *   <li><b>固定持有期</b>：期末收盘卖，不做止损止盈也不看盘中最大浮盈浮亏 ——
 *       那是策略回测，不是「这条信号本身好不好用」。</li>
 *   <li><b>平盘不进退分母</b>：收益恰为 0 的单列 flats。4 胜 4 负 2 平算成 40% 胜率是错的。</li>
 *   <li><b>样本不足不下结论</b>：少于 {@link MIN_SAMPLE} 笔只在界面上标注，不给「胜率 100%」
 *       这种基于 1 笔的说法。</li>
 *   <li><b>不使用未来数据</b>：突破阻力位规则只用信号日之前 60 根算阻力位，
 *       拿全序列算阻力再回头找「突破」是典型的未来函数。</li>
 * </ul>
 */
import { sma } from './stockIndicators.ts'
import { calcKDJ, calcMACD, calcRSI } from './techIndicators.ts'
import { calcSupportResistance } from './supportResistance.ts'
import { getLimitPct } from './marketLimit.ts'

/** 统计的持有期（交易日） */
export const HOLD_PERIODS = [1, 3, 5, 10] as const
/** 少于该笔数视为样本不足 */
export const MIN_SAMPLE = 10
/** 低于该根数不做回测（MA20 / 20 日均量需要预热） */
export const MIN_BARS = 60
/** 回测起始下标：预留 60 根做指标与阻力位预热 */
const START_INDEX = 60

export interface BacktestBar {
  date: string
  open: number
  high: number
  low: number
  close: number
  volume: number
}

/** 单个持有期的统计结果 */
export interface PeriodSummary {
  /** 样本笔数 */
  trades: number
  /** 参与胜率计算的笔数（= 胜 + 负，不含平盘） */
  decided: number
  /** 平盘笔数 */
  flats: number
  /** 胜率 %，无有效样本为 null */
  winRate: number | null
  /** 单笔期望收益 %（= 平均收益） */
  expectancy: number | null
  avgRet: number | null
  avgWin: number | null
  avgLoss: number | null
  best: number | null
  worst: number | null
  /** 最大回撤 %（按逐笔复利净值算） */
  maxDrawdown: number | null
  /** 累计收益 %（逐笔复利） */
  totalReturn: number | null
}

export interface RuleResult {
  id: string
  name: string
  hint: string
  /** 历史触发次数 */
  signalCount: number
  byPeriod: Record<number, PeriodSummary>
  /** 最新一根 K 线上是否触发 */
  active: boolean
  /** 最近一次触发距最新一根多少根，从没触发为 null */
  barsAgo: number | null
  lastHitDate: string | null
  /** 该规则在当前这根 K 线上是否真正参与过判定（未参与就不能说「未触发」） */
  evaluated: boolean
}

export interface BacktestResult {
  bars: number
  /** 数据是否够跑回测 */
  enough: boolean
  from: string | null
  to: string | null
  asOf: string | null
  periods: readonly number[]
  rules: RuleResult[]
}

interface Indicators {
  closes: number[]
  macd: ({ dif: number; dea: number; macd: number } | null)[]
  kdj: { k: number; d: number; j: number }[]
  rsi: (number | null)[]
  ma5: (number | null)[]
  ma10: (number | null)[]
  ma20: (number | null)[]
  volMa20: (number | null)[]
  limitPct: number
}

/** 把指标序列右对齐补到与 K 线等长（RSI 这类会比输入短） */
function alignToLength<T>(series: readonly T[], length: number): (T | null)[] {
  const out: (T | null)[] = new Array(length).fill(null)
  const offset = length - series.length
  for (let i = 0; i < series.length; i++) {
    if (offset + i >= 0) out[offset + i] = series[i]
  }
  return out
}

/** 一次性算好全部规则共用的指标，避免逐规则重复运算 */
function prepareIndicators(bars: readonly BacktestBar[], code: string): Indicators {
  const closes = bars.map((b) => Number(b.close) || 0)
  const volumes = bars.map((b) => Number(b.volume) || 0)
  return {
    closes,
    macd: alignToLength(calcMACD(closes), closes.length),
    kdj: alignToLength(calcKDJ(bars), closes.length) as { k: number; d: number; j: number }[],
    rsi: alignToLength(calcRSI(closes, 14), closes.length),
    ma5: sma(closes, 5),
    ma10: sma(closes, 10),
    ma20: sma(closes, 20),
    volMa20: sma(volumes, 20),
    limitPct: getLimitPct(code)
  }
}

/** 前一日收盘 → 当日收盘涨跌幅（%） */
function dayChangePct(bars: readonly BacktestBar[], i: number): number | null {
  if (i < 1) return null
  const prev = Number(bars[i - 1]?.close) || 0
  const cur = Number(bars[i]?.close) || 0
  if (prev <= 0) return null
  return ((cur - prev) / prev) * 100
}

interface Rule {
  id: string
  name: string
  hint: string
  detect: (i: number, bars: readonly BacktestBar[], ind: Indicators) => boolean
}

const RULES: Rule[] = [
  {
    id: 'ma_cross',
    name: 'MA5 上穿 MA10',
    hint: '短均线金叉，趋势转折的最基础信号',
    detect: (i, _bars, ind) => {
      const { ma5, ma10 } = ind
      if (ma5[i - 1] == null || ma10[i - 1] == null || ma5[i] == null || ma10[i] == null) return false
      return (ma5[i - 1] as number) <= (ma10[i - 1] as number) && (ma5[i] as number) > (ma10[i] as number)
    }
  },
  {
    id: 'ma_stack',
    name: '多头排列成立',
    hint: 'MA5 > MA10 > MA20 首次形成',
    detect: (i, _bars, ind) => {
      const { ma5, ma10, ma20 } = ind
      if (ma5[i - 1] == null || ma10[i - 1] == null || ma20[i - 1] == null) return false
      if (ma5[i] == null || ma10[i] == null || ma20[i] == null) return false
      const now = (ma5[i] as number) > (ma10[i] as number) && (ma10[i] as number) > (ma20[i] as number)
      const prev =
        (ma5[i - 1] as number) > (ma10[i - 1] as number) && (ma10[i - 1] as number) > (ma20[i - 1] as number)
      return now && !prev
    }
  },
  {
    id: 'macd_cross',
    name: 'MACD 金叉',
    hint: 'DIF 上穿 DEA',
    detect: (i, _bars, ind) => {
      const a = ind.macd[i - 1]
      const b = ind.macd[i]
      if (!a || !b) return false
      return a.dif <= a.dea && b.dif > b.dea
    }
  },
  {
    id: 'kdj_low_cross',
    name: 'KDJ 低位金叉',
    hint: 'K 上穿 D 且 K < 30（超卖区反弹）',
    detect: (i, _bars, ind) => {
      const a = ind.kdj[i - 1]
      const b = ind.kdj[i]
      if (!a || !b) return false
      return a.k <= a.d && b.k > b.d && b.k < 30
    }
  },
  {
    id: 'rsi_recover',
    name: 'RSI14 上穿 30',
    hint: '超卖区回升',
    detect: (i, _bars, ind) => {
      const prev = ind.rsi[i - 1]
      const cur = ind.rsi[i]
      if (prev == null || cur == null) return false
      return prev <= 30 && cur > 30
    }
  },
  {
    id: 'break_resistance',
    name: '突破前高阻力位',
    hint: '收盘价上破近 60 日波段高点/斐波那契阻力',
    detect: (i, bars) => {
      if (i < START_INDEX + 1) return false
      // 只用信号日之前的数据，避免用到未来信息
      const { resistance } = calcSupportResistance(bars.slice(i - 60, i))
      if (resistance.length === 0) return false
      const nearest = Math.min(...resistance.map((r) => Number(r.price) || 0))
      if (!(nearest > 0)) return false
      const prevClose = Number(bars[i - 1].close) || 0
      return prevClose <= nearest && (Number(bars[i].close) || 0) > nearest
    }
  },
  {
    id: 'volume_surge',
    name: '放量上涨',
    hint: '成交量 ≥ 1.8× 前 20 日均量 且 涨幅 > 2%',
    detect: (i, bars, ind) => {
      const vm = ind.volMa20[i - 1]
      if (vm == null || vm <= 0) return false
      const chg = dayChangePct(bars, i)
      if (chg == null) return false
      return (Number(bars[i].volume) || 0) >= 1.8 * (vm as number) && chg > 2
    }
  },
  {
    id: 'near_limit_up',
    name: '逼近涨停',
    hint: '涨幅 ≥ 涨停幅度的 70%（港股无涨跌停，不适用）',
    detect: (i, bars, ind) => {
      if (!ind.limitPct) return false
      const chg = dayChangePct(bars, i)
      if (chg == null) return false
      return chg >= ind.limitPct * 0.7
    }
  }
]

/** 单笔收益汇总 */
function summarize(returns: readonly number[]): PeriodSummary {
  const n = returns.length
  if (n === 0) {
    return {
      trades: 0,
      decided: 0,
      flats: 0,
      winRate: null,
      expectancy: null,
      avgRet: null,
      avgWin: null,
      avgLoss: null,
      best: null,
      worst: null,
      maxDrawdown: null,
      totalReturn: null
    }
  }

  let wins = 0
  let losses = 0
  let flats = 0
  let sum = 0
  let grossWin = 0
  let grossLoss = 0
  let best = -Infinity
  let worst = Infinity
  let equity = 1
  let peak = 1
  let maxDd = 0

  for (const r of returns) {
    sum += r
    if (r > 0) {
      wins++
      grossWin += r
    } else if (r < 0) {
      losses++
      grossLoss += r
    } else {
      flats++
    }
    if (r > best) best = r
    if (r < worst) worst = r
    equity *= 1 + r / 100
    if (equity > peak) peak = equity
    const dd = peak > 0 ? ((peak - equity) / peak) * 100 : 0
    if (dd > maxDd) maxDd = dd
  }

  const decided = wins + losses
  return {
    trades: n,
    decided,
    flats,
    winRate: decided > 0 ? (wins / decided) * 100 : null,
    expectancy: sum / n,
    avgRet: sum / n,
    avgWin: wins > 0 ? grossWin / wins : null,
    avgLoss: losses > 0 ? grossLoss / losses : null,
    best,
    worst,
    maxDrawdown: maxDd,
    totalReturn: (equity - 1) * 100
  }
}

/** 非重叠交易 + 固定持有期 */
function backtestRule(hits: readonly boolean[], closes: readonly number[], hold: number): PeriodSummary {
  const returns: number[] = []
  let i = 0
  while (i < hits.length) {
    if (hits[i] && i + hold < closes.length) {
      const entry = closes[i]
      const exit = closes[i + hold]
      if (entry > 0) {
        returns.push(((exit - entry) / entry) * 100)
        i += hold // 持有期内不再开仓
        continue
      }
    }
    i++
  }
  return summarize(returns)
}

/**
 * 对日 K 数组跑全部规则（纯函数，便于单测）。
 *
 * @param bars 日 K，按日期升序
 * @param code 股票代码（用于判断涨跌停幅度）
 */
export function analyzeBars(bars: readonly BacktestBar[], code: string): BacktestResult {
  const list = (Array.isArray(bars) ? bars : []).filter((b) => b && Number(b.close) > 0)
  if (list.length < MIN_BARS) {
    return {
      bars: list.length,
      enough: false,
      from: null,
      to: null,
      asOf: null,
      periods: HOLD_PERIODS,
      rules: []
    }
  }

  const ind = prepareIndicators(list, code)
  const start = Math.min(START_INDEX, list.length - 1)
  const last = list.length - 1

  const rules = RULES.map((rule) => {
    const hits = new Array<boolean>(list.length).fill(false)
    let hitCount = 0
    let lastHit = -1
    for (let i = Math.max(start, 1); i < list.length; i++) {
      let ok = false
      try {
        ok = rule.detect(i, list, ind) === true
      } catch {
        ok = false // 单条规则异常不该中断整轮回测
      }
      if (ok) {
        hits[i] = true
        hitCount++
        lastHit = i
      }
    }

    const byPeriod: Record<number, PeriodSummary> = {}
    for (const hold of HOLD_PERIODS) {
      byPeriod[hold] = backtestRule(hits, ind.closes, hold)
    }

    let active = false
    try {
      active = rule.detect(last, list, ind) === true
    } catch {
      active = false
    }

    return {
      id: rule.id,
      name: rule.name,
      hint: rule.hint,
      signalCount: hitCount,
      byPeriod,
      active,
      barsAgo: lastHit < 0 ? null : last - lastHit,
      lastHitDate: lastHit < 0 ? null : list[lastHit]?.date ?? null,
      evaluated: last >= Math.max(start, 1)
    }
  })

  return {
    bars: list.length,
    enough: true,
    from: list[0]?.date ?? null,
    to: list[list.length - 1]?.date ?? null,
    asOf: list[last]?.date ?? null,
    periods: HOLD_PERIODS,
    rules
  }
}
