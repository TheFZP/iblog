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

/**
 * 主力资金流向（当日分档快照）。单位：净流入为**万元**，占比为百分数。
 *
 * <p>东财口径的「主力」= 超大单 + 大单。净流入 = 买入额 - 卖出额，可以为负。</p>
 */
export interface MoneyFlow {
  /** 净流入对应的交易日 yyyy-MM-dd */
  date: string
  /** 主力净流入（万元） */
  mainNetInflow: number
  /** 主力净占比（%） */
  mainNetPct: number
  superLargeNet: number
  superLargePct: number
  largeNet: number
  largePct: number
  mediumNet: number
  mediumPct: number
  smallNet: number
  smallPct: number
  /** 是否当前交易日盘中实时数据；false 表示展示的是最近交易日定稿数据 */
  current: boolean
  /** 上游暂不提供该股资金流（部分北交所标的） */
  unavailable: boolean
  /**
   * `unavailable` 的原因：`network` 表示上游连不上（稍后重试即可），
   * 空表示上游确实没有这只标的的资金流。
   */
  reason?: string
}

/** 主力资金流向历史（近 N 个交易日，按日期升序） */
export interface MoneyFlowHistoryItem {
  date: string
  mainNetInflow: number
  superLargeNet: number
  largeNet: number
  mediumNet: number
  smallNet: number
  /** 当日收盘价 */
  close: number
}

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

export function fetchMoneyFlow(code: string) {
  return get<MoneyFlow>('/stock/moneyflow', { code })
}

export function fetchMoneyFlowHistory(code: string, limit = 30) {
  return get<MoneyFlowHistoryItem[]>('/stock/moneyflow/history', { code, limit })
}

/** 个股相对沪深300 的阶段表现。timeType：1=近1月 2=近3月 3=近6月 4=今年以来 */
export interface MarketPerformance {
  timeType: number
  /** 个股区间涨跌幅（%） */
  changeRate: number
  /** 沪深300 同期涨跌幅（%） */
  hs300ChangeRate: number
}

/** 同行业公司的营收排名。金额单位为**元**（展示时自己除 1e8 转亿） */
export interface RevenueRanking {
  stockCode: string
  stockName: string
  totalOperateIncome: number
  /** 营收在行业内排名，1 = 最大 */
  totalOperateIncomeRank: number
  netProfit: number
  netProfitRank: number
  totalCap: number
}

export interface IndustryAverage {
  totalOperateIncome: number
  netProfit: number
  totalCap: number
}

/** 个股行业分析。港股无此数据，返回 `industryName: '港股'` 的空结构 */
export interface IndustryData {
  industryName: string
  marketPerformance: MarketPerformance[]
  revenueRanking: RevenueRanking[]
  industryAverage: IndustryAverage | null
  /** 报表期，如「2026年中报」 */
  reportType: string
}

export function fetchIndustry(code: string) {
  return get<IndustryData>('/stock/industry', { code })
}
