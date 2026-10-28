/**
 * 涨跌停幅度口径。
 *
 * <p>规则：主板 ±10% | 创业板(30) / 科创板(68) ±20% | 北交所 ±30% | 港股无涨跌停。
 * ST/*ST 的涨跌幅与所属板块一致（不再是 ±5%），所以只看代码、不需要股票名称。</p>
 *
 * <p>回测里「逼近涨停」规则要靠这个幅度判断，口径错了会把 20% 的创业板信号
 * 全部误判成不达标，所以单独抽出来，改规则只改这一处。</p>
 */

/** 是否港股（5 位数字代码） */
export function isHkCode(code: string | null | undefined): boolean {
  return /^\d{5}$/.test(String(code ?? ''))
}

/** 是否北交所（43 / 82 / 83 / 87 / 88 / 92 开头） */
export function isBjCode(code: string | null | undefined): boolean {
  return /^(43|82|83|87|88|92)/.test(String(code ?? ''))
}

/**
 * 涨跌停幅度（%）。
 * @returns 港股返回 0（无涨跌停限制），其余返回 10 / 20 / 30
 */
export function getLimitPct(code: string | null | undefined): number {
  const c = String(code ?? '')
  if (!c) return 10
  if (isHkCode(c)) return 0
  if (isBjCode(c)) return 30
  if (c.startsWith('30')) return 20
  if (c.startsWith('68')) return 20
  return 10
}

/** 涨停触发阈值（通知用，留 0.5% 容差）；港股返回 0 表示不适用 */
export function getLimitThreshold(code: string | null | undefined): number {
  const pct = getLimitPct(code)
  return pct === 0 ? 0 : pct - 0.5
}
