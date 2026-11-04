import { get } from './request'

/** 新闻情绪分（候选/持仓表格用） */
export interface StockNewsItem {
  code: string
  name: string
  /** -100~100，正利好 / 负利空 */
  score: number
  /** 0~1 */
  confidence: number
  /** 利好 / 中性 / 利空 */
  label: string
  summary: string
  /** ai / keyword */
  model: string
  /** MM-dd HH:mm */
  updatedAt: string
}

/** 单条新闻标题 */
export interface StockNewsHeadline {
  title: string
  source: string
  date: string
  /** stock=个股 / sector=板块 */
  scope: string
}

/** 新闻面板详情 */
export interface StockNewsDetail extends StockNewsItem {
  headlines: StockNewsHeadline[]
}

/** 批量查询今日新闻分（最多 80 只） */
export function fetchStockNewsBatch(codes: string[]) {
  return get<StockNewsItem[]>('/stock/news/batch', { codes: codes.join(',') })
}

/** 单只股票新闻详情（含原始标题） */
export function fetchStockNewsDetail(code: string) {
  return get<StockNewsDetail | null>('/stock/news', { code })
}
