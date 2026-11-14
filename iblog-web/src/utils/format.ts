/**
 * 时间格式化：后端返回的是 yyyy-MM-dd HH:mm:ss。
 */
export function formatDate(value?: string | null, withTime = false): string {
  if (!value) {
    return ''
  }
  const normalized = value.replace('T', ' ')
  return withTime ? normalized.slice(0, 16) : normalized.slice(0, 10)
}

/**
 * 估算阅读时长（按每分钟 400 字）。
 *
 * <p>按原始 Markdown 长度算会把内联 HTML/SVG 和标签符号也当成正文：
 * 一篇带流程图的文章会被估成二十多分钟。所以先去掉标签、图片和 Markdown 符号，
 * 只统计真正要读的文字。</p>
 */
export function estimateReadingTime(content?: string | null): number {
  if (!content) {
    return 1
  }
  const text = content
    // 代码块保留内容、去掉围栏
    .replace(/```[^\n]*\n?/g, '')
    .replace(/<[^>]*>/g, '')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, '')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/[#>*_`~|]/g, '')
    .replace(/\s+/g, '')
  return Math.max(1, Math.round(text.length / 400))
}
