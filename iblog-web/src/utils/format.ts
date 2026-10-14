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
 */
export function estimateReadingTime(content?: string | null): number {
  if (!content) {
    return 1
  }
  return Math.max(1, Math.round(content.length / 400))
}
