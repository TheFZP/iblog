/**
 * 按股票代码推导板块标签。
 *
 * <p>科创板 688/689 →「科」，创业板 300/301 →「创」。其余（主板、北交所、港股）
 * 不显示板块标签。融资融券的「融」需要交易所标的清单，见 useMarginTargets。</p>
 */
export type BoardTag = '科' | '创' | ''

export function boardTag(code: string | null | undefined): BoardTag {
  const c = String(code ?? '')
  if (c.startsWith('68')) return '科'
  if (c.startsWith('30')) return '创'
  return ''
}