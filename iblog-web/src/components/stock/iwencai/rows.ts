/**
 * 问财结果的两块纯逻辑：本地分页 + 「AI 分析」预设文本。
 *
 * <p>抽成不依赖 Vue 的独立模块，是为了能在 `scripts/iwencai-helpers.test.mjs` 里直接断言 ——
 * 分页边界（最后一页不满、页码越界）和「只取前 30 行」的截断都很容易在改动中悄悄坏掉，
 * 坏掉时页面不会报错，只会少几行数据。</p>
 */

/**
 * 按页切片。
 *
 * <p>问财服务端会忽略 `page`（返回内容与第 1 页相同），所以翻页只能对一次拉回来的整批行做切片；
 * 因此这里接收的是「全部行」，而不是远程分页的某一页。</p>
 *
 * @param rows     全部结果行
 * @param page     页码，从 1 开始（越界会被夹到第一页，不抛错）
 * @param pageSize 每页行数，<= 0 时返回空数组
 */
export function sliceRows<T>(rows: T[], page: number, pageSize: number): T[] {
  if (rows.length === 0 || pageSize <= 0) {
    return []
  }
  const safePage = Number.isFinite(page) ? Math.max(1, Math.trunc(page)) : 1
  const start = (safePage - 1) * pageSize
  return rows.slice(start, start + pageSize)
}

/** 总页数：空结果也算 1 页，这样底部永远显示「第 1 / 1 页」而不是「第 1 / 0 页」。 */
export function pageCount(total: number, pageSize: number): number {
  if (pageSize <= 0 || total <= 0) {
    return 1
  }
  return Math.max(1, Math.ceil(total / pageSize))
}

/**
 * 把页码夹到 [1, totalPages]。
 *
 * <p>页码是本地状态，而总页数会随「每页行数」切换和结果变少而变化：
 * 停在最后一页时把每页行数调大，旧页码就会越界并切出空表格，所以渲染前必须夹一次。</p>
 */
export function clampPage(page: number, totalPages: number): number {
  const max = Math.max(1, Math.trunc(totalPages))
  if (!Number.isFinite(page)) {
    return 1
  }
  return Math.min(Math.max(1, Math.trunc(page)), max)
}

/**
 * 拼给 AI 的纯文本表格：首行是列名，其后每行一条记录，单元格用 ` | ` 分隔。
 *
 * <p>只取前 `limit` 行：整批可能有 100 行，全塞进提示词既贵又会把上下文挤掉。</p>
 */
export function buildPresetText(columns: string[], rows: string[][], limit = 30): string {
  const max = Number.isFinite(limit) ? Math.max(0, Math.trunc(limit)) : 0
  const lines: string[] = []
  if (columns.length > 0) {
    lines.push(columns.join(' | '))
  }
  for (const row of rows.slice(0, max)) {
    // 按列名下标的长度取值：问财的行是二维数组，行比列短时补空串而不是 undefined
    lines.push(columns.map((_, index) => (row[index] ?? '').trim()).join(' | '))
  }
  return lines.join('\n')
}
