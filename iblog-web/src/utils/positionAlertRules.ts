/**
 * 持仓止损止盈的**纯判定逻辑**。
 *
 * <p>刻意做成零依赖的独立模块，不 import Vue、不碰 localStorage、不读时间：
 * 这样它既能被 composable 复用，也能被 `node --test` 直接测试。</p>
 *
 * <p>为什么值得为这么几行单独建文件：这段逻辑里每个符号改一下行为就反了 ——
 * 边界取开还是闭、盈亏用成本还是昨收、去重按什么粒度。之前它埋在 composable 里，
 * 只能靠手动点界面验证，改了心里没底。</p>
 */

export type PositionAlertKind = 'stop_loss' | 'take_profit'

/**
 * 归一化百分比输入。
 *
 * <p>0、负数、NaN 一律视为「未启用」—— 用户把输入框清空时 v-model 会给出 ''，
 * `Number('')` 是 0，如果不拦就会变成「亏 0% 就提醒」，等于每轮都触发。</p>
 */
export function normalizePct(v: unknown): number | null {
  const n = typeof v === 'number' ? v : Number(v)
  return Number.isFinite(n) && n > 0 ? n : null
}

/** 盈亏百分比，通知文案与判定共用同一口径 */
export function profitPercent(buyPrice: number, price: number): number {
  if (!(buyPrice > 0)) {
    return 0
  }
  return ((price - buyPrice) / buyPrice) * 100
}

/** 一次判定的输入 */
export interface JudgeInput {
  /** 成本价 */
  buyPrice: number
  /** 现价 */
  price: number
  stopLossPct?: number | null
  takeProfitPct?: number | null
  /** 今天已经报过的类型，用于去重 */
  done?: readonly string[]
}

/**
 * 判定是否触发止损/止盈。
 *
 * <p>边界取<b>闭区间</b>：刚好亏到 8% 就该提醒，要求亏到 8.0001% 才报是反直觉的。</p>
 *
 * @returns 本次应触发的类型列表（空数组表示不该报）
 */
export function judgePositionAlert(input: JudgeInput): PositionAlertKind[] {
  const { buyPrice, price } = input
  // 没有成本价就没有盈亏可言（未填写成本的记录）
  if (!(buyPrice > 0) || !(price > 0)) {
    return []
  }
  const stopLoss = normalizePct(input.stopLossPct)
  const takeProfit = normalizePct(input.takeProfitPct)
  if (stopLoss == null && takeProfit == null) {
    return []
  }

  const pct = profitPercent(buyPrice, price)
  const done = input.done ?? []
  const hits: PositionAlertKind[] = []
  if (stopLoss != null && pct <= -stopLoss && !done.includes('stop_loss')) {
    hits.push('stop_loss')
  }
  if (takeProfit != null && pct >= takeProfit && !done.includes('take_profit')) {
    hits.push('take_profit')
  }
  return hits
}
