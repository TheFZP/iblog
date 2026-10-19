import { get } from './request'

/**
 * 股票行情接口。
 *
 * <p>后端 /api/stock 代理了腾讯行情 / 同花顺热榜。原本桌面版是 Rust 直连上游，
 * 网页版必须走后端：浏览器直连会被 CORS 拦，且腾讯返回 GBK 无 charset 头。</p>
 */

export interface StockQuote {
  code: string
  name: string
  /** 现价 */
  price: number
  /** 昨收 */
  prevClose: number
  open: number
  /** 成交量（手） */
  volume: number
  /** 成交额（万元） */
  turnover: number
  /** 涨跌额 */
  change: number
  /** 涨跌幅 % */
  changePct: number
  high: number
  low: number
  /** 换手率 % */
  turnoverRate: number
  /** 市盈率 */
  pe: number
  /** 振幅 % */
  amplitude: number
  market: string
  /** 币种符号：¥ 或 HK$ */
  currency: string
  suspended: boolean
  amount: number
  /** 上游行情时间 yyyy-MM-dd HH:mm:ss；空串表示上游未给 */
  quoteTime?: string
}

export interface SearchResult {
  code: string
  name: string
  market: string
  displayCode: string
}

export interface MarketIndex {
  code: string
  name: string
  price: number
  change: number
  changePct: number
  /** 上游行情时间 yyyy-MM-dd HH:mm:ss；空串表示上游未给 */
  quoteTime?: string
}

export interface KlineItem {
  date: string
  open: number
  close: number
  high: number
  low: number
  volume: number
  turnover: number
}

export interface IntradayItem {
  time: string
  price: number
  avgPrice: number
  volume: number
  turnover: number
  vwap: number
}

export interface IntradayData {
  items: IntradayItem[]
}

/** 盘口单档 */
export interface OrderBookLevel {
  price: number
  /** 委托量（手） */
  volume: number
}

export interface OrderBook {
  /** 五档是否可用；港股恒为 false */
  available: boolean
  /** 卖五到卖一（价格由高到低） */
  asks: OrderBookLevel[]
  /** 买一到买五（价格由高到低） */
  bids: OrderBookLevel[]
}

export interface HotStockItem {
  code: string
  name: string
  /** 热度值（字符串，源站原样） */
  rate: string
  riseAndFall: number
  hotRankChg: number
  order: number
  market: number
  tags: string[]
  popularityTag: string
}

export type KlinePeriod = 'day' | 'week' | 'month' | 'm5' | 'm15' | 'm30' | 'm60'

export function fetchQuote(code: string) {
  return get<StockQuote>('/stock/quote', { code })
}

export function fetchQuotes(codes: string[]) {
  return get<StockQuote[]>('/stock/quotes', { codes: codes.join(',') })
}

export function searchStock(keyword: string) {
  return get<SearchResult[]>('/stock/search', { keyword })
}

export function fetchKline(code: string, period: KlinePeriod = 'day') {
  return get<KlineItem[]>('/stock/kline', { code, period })
}

export function fetchIntraday(code: string) {
  return get<IntradayData>('/stock/intraday', { code })
}

export function fetchIndices() {
  return get<MarketIndex[]>('/stock/indices')
}

export function fetchHotList() {
  return get<HotStockItem[]>('/stock/hotlist')
}

export function fetchOrderBook(code: string) {
  return get<OrderBook>('/stock/orderbook', { code })
}
