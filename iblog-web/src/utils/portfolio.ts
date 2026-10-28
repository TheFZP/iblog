/**
 * 持仓组合层分析 —— 把「逐只盈亏」升级成「组合视角」。
 *
 * <p>逐只盈亏只告诉你这只赚了多少，看不出「我是不是全仓压在一只票上」。</p>
 * 这里补的是组合层指标：
 * 当日盈亏、仓位权重、集中度（HHI / 前三大权重）、资产类别、行业分布、加权 PE。
 *
 * <h3>口径约定（改动前先读）</h3>
 * <ul>
 *   <li>金额默认按人民币，港股用 {@code fxRate} 折算 —— 汇率由界面传入，
 *       系统不内置汇率源（写死一个汇率然后显示「精确到分」比不给更糟）。</li>
 *   <li>资产类别只区分 A 股 / 港股，不臆造板块。</li>
 *   <li><b>缺数据必须如实披露</b>：缺昨收的持仓不进当日盈亏合计（并列 missing 数），
 *       缺行业标签的不并进「无行业」，而是单列覆盖率。把「没取到」当成「没有」，
 *       会让「行业集中度」这种指标在数据不全时给出完全错误的结论。</li>
 * </ul>
 */
import { isHkCode } from './marketLimit.ts'

/** 组合分析的单行输入 */
export interface PortfolioInput {
  code: string
  name?: string
  quantity: number
  /** 成本价 */
  buyPrice: number
  /** 现价（缺行情时调用方用成本价兜底） */
  currentPrice: number
  /** 昨收，缺了当日盈亏算不出来 */
  prevClose?: number | null
  /** 市盈率，亏损股为负或 0 */
  pe?: number | null
  /** 行业名，取不到就留空 */
  industry?: string | null
}

export interface PortfolioOptions {
  /** 港币兑人民币汇率，默认 1（即不折算） */
  fxRate?: number
}

export interface PortfolioRow extends PortfolioInput {
  rate: number
  /** 原币市值 */
  marketValue: number
  /** 折合人民币市值 */
  marketValueCNY: number
  costCNY: number
  profitCNY: number
  /** 当日盈亏（折人民币）；缺昨收为 null */
  dayProfitCNY: number | null
  /** 当日涨跌幅 %；缺昨收为 null */
  dayChangePct: number | null
  /** 占组合权重 % */
  weightPct: number
  assetClass: 'A股' | '港股'
  industry: string
  industryKnown: boolean
}

export interface IndustryGroup {
  key: string
  count: number
  marketValueCNY: number
  profitCNY: number
  /** 占「已取到行业标签的持仓」的比例 */
  weightPct: number
  /** 占整个组合的比例（与 maxIndustryWeightPct 同口径） */
  portfolioPct: number
  codes: string[]
}

export interface PortfolioAnalysis {
  rows: PortfolioRow[]
  count: number
  isEmpty: boolean
  fxRate: number
  hasHK: boolean
  /** 有港股但没设汇率时的提示（界面要显式说出来） */
  fxWarning: string | null
  totalCostCNY: number
  totalMarketValueCNY: number
  totalProfitCNY: number
  totalProfitPctCNY: number
  dayProfit: { value: number; covered: number; missing: number; pct: number | null }
  dayBreadth: { up: number; down: number; flat: number }
  dayBestWorst: { best: PortfolioRow | null; worst: PortfolioRow | null }
  topHolding: PortfolioRow | null
  top3WeightPct: number
  /** 赫芬达尔指数 0~10000 */
  hhi: number
  concentration: { level: string; hint: string }
  byAssetClass: { key: string; count: number; marketValueCNY: number; profitCNY: number; weightPct: number }[]
  byIndustry: {
    groups: IndustryGroup[]
    knownCount: number
    unknownCount: number
    /** 行业标签覆盖率 % */
    coveragePct: number
    knownValueCNY: number
  }
  maxIndustryWeightPct: number | null
  weightedPE: { value: number | null; covered: number; excluded: number }
}

/** 资产类别：只区分 A 股 / 港股 */
export function assetClassOf(code: string): 'A股' | '港股' {
  return isHkCode(code) ? '港股' : 'A股'
}

/**
 * 纯函数：把持仓行算成组合分析结果（不读 localStorage、不发请求，便于单测）。
 */
export function analyzePortfolio(
  items: readonly PortfolioInput[],
  options: PortfolioOptions = {}
): PortfolioAnalysis {
  const fxRate = Number(options.fxRate)
  const rate0 = Number.isFinite(fxRate) && fxRate > 0 ? fxRate : 1
  const list = (Array.isArray(items) ? items : []).filter((p) => p && p.code)

  const hasHK = list.some((p) => isHkCode(p.code))
  const fxWarning =
    hasHK && rate0 === 1 ? '持仓含港股，未设置汇率，按 1:1 估算（金额仅供比例参考）' : null

  // 先算原币市值，才能拿到总市值做权重分母
  const raw = list.map((p) => {
    const rate = isHkCode(p.code) ? rate0 : 1
    const quantity = Number(p.quantity) || 0
    const buyPrice = Number(p.buyPrice) || 0
    const price = Number(p.currentPrice)
    const currentPrice = Number.isFinite(price) && price > 0 ? price : buyPrice
    const marketValue = currentPrice * quantity
    return { p, rate, quantity, buyPrice, currentPrice, marketValue }
  })
  const total = raw.reduce((s, r) => s + r.marketValue * r.rate, 0)

  const rows: PortfolioRow[] = raw.map(({ p, rate, quantity, buyPrice, currentPrice, marketValue }) => {
    const marketValueCNY = marketValue * rate
    const costCNY = buyPrice * quantity * rate
    const prevClose = Number(p.prevClose)
    const hasPrev = Number.isFinite(prevClose) && prevClose > 0 && currentPrice > 0
    const dayProfitCNY = hasPrev ? (currentPrice - prevClose) * quantity * rate : null
    const dayChangePct = hasPrev ? ((currentPrice - prevClose) / prevClose) * 100 : null
    const industry = String(p.industry ?? '').trim()
    return {
      ...p,
      // 必须显式覆盖：缺行情时 currentPrice 已回落到成本价，
      // 但 `...p` 会把原始的空值/0 又盖回来，界面上就会显示「现价 0」而市值却是按成本算的
      currentPrice,
      rate,
      marketValue,
      marketValueCNY,
      costCNY,
      profitCNY: marketValueCNY - costCNY,
      dayProfitCNY,
      dayChangePct,
      weightPct: total > 0 ? (marketValueCNY / total) * 100 : 0,
      assetClass: assetClassOf(p.code),
      industry,
      industryKnown: industry.length > 0
    }
  })

  const totalCostCNY = rows.reduce((s, r) => s + r.costCNY, 0)
  const totalMarketValueCNY = rows.reduce((s, r) => s + r.marketValueCNY, 0)
  const totalProfitCNY = totalMarketValueCNY - totalCostCNY

  // ---- 当日盈亏 ----
  const dayKnown = rows.filter((r) => r.dayProfitCNY != null)
  const dayValue = dayKnown.reduce((s, r) => s + (r.dayProfitCNY as number), 0)
  const dayBase = dayKnown.reduce((s, r) => s + (r.marketValueCNY - (r.dayProfitCNY as number)), 0)
  const dayProfit = {
    value: dayValue,
    covered: dayKnown.length,
    missing: rows.length - dayKnown.length,
    pct: dayKnown.length > 0 && dayBase > 0 ? (dayValue / dayBase) * 100 : null
  }

  // ---- 涨跌家数 ----
  let up = 0
  let down = 0
  let flat = 0
  for (const r of rows) {
    if (r.dayChangePct == null) continue
    if (r.dayChangePct > 0) up++
    else if (r.dayChangePct < 0) down++
    else flat++
  }

  // ---- 当日最赚 / 最亏 ----
  const sortedByDay = [...dayKnown].sort((a, b) => (b.dayProfitCNY as number) - (a.dayProfitCNY as number))
  const dayBestWorst = {
    best: sortedByDay[0] ?? null,
    worst: sortedByDay.length > 0 ? sortedByDay[sortedByDay.length - 1] : null
  }

  // ---- 集中度 ----
  const sortedByWeight = [...rows].sort((a, b) => b.weightPct - a.weightPct)
  const topHolding = sortedByWeight[0] ?? null
  const top3WeightPct = sortedByWeight.slice(0, 3).reduce((s, r) => s + r.weightPct, 0)
  const hhi = rows.reduce((s, r) => s + r.weightPct * r.weightPct, 0)
  const concentration = (() => {
    if (rows.length <= 1) return { level: '单一', hint: '仅一只持仓，无分散可言' }
    if (hhi > 2500) return { level: '高度集中', hint: '单票波动主导组合，注意回撤' }
    if (hhi >= 1500) return { level: '中度集中', hint: '分散度一般' }
    return { level: '较为分散', hint: '个股风险相对摊薄' }
  })()

  // ---- 按资产类别聚合 ----
  const classMap = new Map<string, { key: string; count: number; marketValueCNY: number; profitCNY: number }>()
  for (const r of rows) {
    const cur = classMap.get(r.assetClass) ?? { key: r.assetClass, count: 0, marketValueCNY: 0, profitCNY: 0 }
    cur.count++
    cur.marketValueCNY += r.marketValueCNY
    cur.profitCNY += r.profitCNY
    classMap.set(r.assetClass, cur)
  }
  const byAssetClass = [...classMap.values()]
    .map((g) => ({ ...g, weightPct: totalMarketValueCNY > 0 ? (g.marketValueCNY / totalMarketValueCNY) * 100 : 0 }))
    .sort((a, b) => b.marketValueCNY - a.marketValueCNY)

  // ---- 按行业聚合（只统计取到标签的） ----
  const known = rows.filter((r) => r.industryKnown)
  const knownValueCNY = known.reduce((s, r) => s + r.marketValueCNY, 0)
  const indMap = new Map<string, IndustryGroup>()
  for (const r of known) {
    const cur =
      indMap.get(r.industry) ??
      ({ key: r.industry, count: 0, marketValueCNY: 0, profitCNY: 0, weightPct: 0, portfolioPct: 0, codes: [] } as IndustryGroup)
    cur.count++
    cur.marketValueCNY += r.marketValueCNY
    cur.profitCNY += r.profitCNY
    cur.codes.push(r.code)
    indMap.set(r.industry, cur)
  }
  const groups = [...indMap.values()]
    .map((g) => ({
      ...g,
      weightPct: knownValueCNY > 0 ? (g.marketValueCNY / knownValueCNY) * 100 : 0,
      portfolioPct: totalMarketValueCNY > 0 ? (g.marketValueCNY / totalMarketValueCNY) * 100 : 0
    }))
    .sort((a, b) => b.marketValueCNY - a.marketValueCNY)
  const byIndustry = {
    groups,
    knownCount: known.length,
    unknownCount: rows.length - known.length,
    coveragePct: rows.length > 0 ? (known.length / rows.length) * 100 : 0,
    knownValueCNY
  }

  // ---- 加权 PE：只统计 PE > 0 ----
  const peValid = rows.filter((r) => Number(r.pe) > 0 && r.marketValueCNY > 0)
  const peNum = peValid.reduce((s, r) => s + Number(r.pe) * r.marketValueCNY, 0)
  const peDen = peValid.reduce((s, r) => s + r.marketValueCNY, 0)
  const weightedPE = {
    value: peValid.length > 0 && peDen > 0 ? peNum / peDen : null,
    covered: peValid.length,
    excluded: rows.length - peValid.length
  }

  return {
    rows,
    count: rows.length,
    isEmpty: rows.length === 0,
    fxRate: rate0,
    hasHK,
    fxWarning,
    totalCostCNY,
    totalMarketValueCNY,
    totalProfitCNY,
    totalProfitPctCNY: totalCostCNY > 0 ? (totalProfitCNY / totalCostCNY) * 100 : 0,
    dayProfit,
    dayBreadth: { up, down, flat },
    dayBestWorst,
    topHolding,
    top3WeightPct,
    hhi,
    concentration,
    byAssetClass,
    byIndustry,
    maxIndustryWeightPct: groups.length > 0 ? groups[0].portfolioPct : null,
    weightedPE
  }
}
