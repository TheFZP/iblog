import { get } from './request'
import type { PageResult } from './article'

/** 官网访问日志明细（blog_visit_log 一行） */
export interface VisitLogItem {
  id: number
  /** 请求路径（不含 query） */
  path: string
  method: string
  ip: string
  userAgent: string | null
  referer: string | null
  /** 业务码（200 为成功） */
  status: number
  /** 耗时（毫秒） */
  costMs: number
  createTime: string
}

/** 今日统计 */
export interface VisitSummary {
  /** 今日访问次数（PV 口径：API 调用次数） */
  todayCount: number
  /** 今日独立 IP 数（UV 近似） */
  todayIpCount: number
  /** 热门路径 Top10 */
  topPaths: { path: string; count: number }[]
}

export function pageVisitLogs(params: { pageNum?: number; pageSize?: number; path?: string }) {
  return get<PageResult<VisitLogItem>>('/admin/visit-logs', params)
}

export function getVisitSummary() {
  return get<VisitSummary>('/admin/visit-logs/summary')
}
