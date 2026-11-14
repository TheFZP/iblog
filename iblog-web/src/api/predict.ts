import service, { get } from './request'

/**
 * 全市场「次日看涨」预测接口。
 *
 * <p>预测在交易日 15:30 生成，次日收盘后回填收益；页面只读，不触发上游扫描。
 * 主指标 hit 是「T+1 开盘买入 → 收盘卖出」的可交易口径，hitClose 是收盘对收盘。</p>
 */

/** 新闻来源（只保留 http/https 链接） */
export interface PredictNewsSource {
  title: string
  url: string
  source: string
  /** 发布时间（原样字符串，可能是 MM-dd HH:mm） */
  publishedAt: string
  /** stock=个股 / sector=板块 */
  scope: string
}

/** 榜单中的一只股票 */
export interface PredictItem {
  rank: number
  code: string
  name: string
  /** 规则分 0~100（含新闻加减分后归一化） */
  score: number
  /** 次日上涨参考概率 % */
  probability: number
  /** HEURISTIC / CALIBRATED */
  probSource: string
  /** 校准样本数 */
  calibrationN: number
  reasons: string
  newsSummary: string
  newsSources: PredictNewsSource[]
  /** T 日涨跌幅 % */
  changePct: number | null
  /** T 日收盘价 */
  prevClose: number | null
  /** 是否可买：1 可买 / 0 不可买 */
  buyable: number | null
  /** T+1 收盘 vs T 收盘 % */
  nextReturnPct: number | null
  /** T+1 开盘买 → 收盘卖 %（主指标） */
  nextOpenReturnPct: number | null
  /** 可交易口径是否命中：1/0，null=待回填 */
  hit: number | null
  /** 收盘对收盘是否上涨：1/0，null=待回填 */
  hitClose: number | null
  /** PENDING / EVALUATED / NO_DATA */
  evalStatus: string
}

export interface PredictDaily {
  date: string
  generatedAt: string
  marketSentiment: number | null
  items: PredictItem[]
}

export interface PredictDateItem {
  date: string
  count: number
  evaluated: number
}

export interface PredictBucket {
  label: string
  samples: number
  evaluated: number
  hits: number
  hitRatePct: number
  avgOpenReturnPct: number
}

export interface PredictDailyStat {
  date: string
  samples: number
  evaluated: number
  hits: number
  hitRatePct: number
  avgOpenReturnPct: number
  avgCloseReturnPct: number
}

export interface PredictStats {
  days: number
  totalSamples: number
  evaluated: number
  hits: number
  hitRatePct: number
  hitCloseHits: number
  hitCloseRatePct: number
  avgNextReturnPct: number
  avgOpenReturnPct: number
  daily: PredictDailyStat[]
  byScore: PredictBucket[]
  byProbability: PredictBucket[]
}

export interface PredictRefresh {
  date: string
  dryRun: boolean
  universe: number
  scored: number
  newsCovered: number
  count: number
  elapsedMs: number
  items: PredictItem[]
}

/** 每日榜单；date 为空时后端返回最近一期 */
export function fetchPredictDaily(date?: string) {
  return get<PredictDaily>('/stock/predict/daily', date ? { date } : undefined)
}

/** 最近有榜单的交易日 */
export function fetchPredictDates(limit = 30) {
  return get<PredictDateItem[]>('/stock/predict/dates', { limit })
}

/** 近 N 日准确率 */
export function fetchPredictStats(days = 30) {
  return get<PredictStats>('/stock/predict/stats', { days })
}

/**
 * 手工触发扫描（需要后端开启 iblog.predict.manual-trigger-enabled）。
 *
 * <p>全市场扫描可能跑几分钟，这里单独把超时放到 5 分钟，避免 15 秒默认超时
 * 把「还在跑」误报成失败。</p>
 */
export function triggerPredictRefresh(dryRun = false) {
  return service.post(
    '/stock/predict/refresh',
    null,
    { params: { dryRun }, timeout: 300000 }
  ) as unknown as Promise<PredictRefresh>
}
