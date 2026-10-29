/**
 * 日内 T+0 信号 —— 纯函数模块（无 Vue、无 DOM、无网络、无随机）。
 *
 * <p>从桌面版 stock-analysis-prh 的 useVolumeSignals / useTrapSignals / useT0Signals 移植，
 * 数据口径改写为 iblog 的分时结构：{@link IntradayItem}.time 是 `HH:mm`，
 * volume 是**本分钟**成交量（后端已用累计量差分得到，单位手），
 * avgPrice / vwap 都是**累计**均价。prh 里的 `p` / `avgP` / `vol` 这类短字段
 * 在这里一律换成 iblog 的完整字段名，不沿用 prh 的字段。</p>
 *
 * <h3>三组能力</h3>
 * <ul>
 *   <li>{@link detectIntradaySignals}：分时量价标记（放量急拉 / 放量急跌 / 无量拉升 /
 *       突破 / 破位 / 顶背离 / 底背离），逐分钟整条扫描。</li>
 *   <li>{@link detectTrapSignals}：诱多 / 诱空**嫌疑**——只输出嫌疑与确认条件，
 *       不给确定性结论（prh 原作如此，此处刻意保留这一克制）。</li>
 *   <li>{@link detectDailyDivergence}：日 K MACD 背离（只吃日 K，不需要分时）。</li>
 * </ul>
 *
 * <h3>两套口径共存（有意为之）</h3>
 * <p>文件后半段还有一组面向**分时图打点**的精简口径：{@link computeT0Signals}、
 * {@link groupMarkers}、{@link summarizeMarkers}，配套类型 {@link T0Marker} /
 * {@link T0SignalOptions}。它与上面的 detect* 不是同一套判据：</p>
 * <ul>
 *   <li>detect* 用 5 分钟动能 + 滚动中位数基准，产出带 barIndex 的信号，喂给信号历史列表与回测；</li>
 *   <li>computeT0Signals 用「上一分钟 → 这一分钟」的单分钟涨跌 + 均量，只产出能在图上
 *       定位的 (time, price, kind)，阈值全部可由调用处覆盖。</li>
 * </ul>
 * <p>同一份分时数据要同时服务「历史列表」与「图上标记」两种消费方，合并成一套必然让
 * 其中一侧的口径被迫妥协，因此两套并存，各自服务各自动线。</p>
 *
 * <h3>去噪（沿用 prh，勿随手放宽）</h3>
 * <ul>
 *   <li>同一分钟只保留一个标记，先命中者优先（扫描顺序即优先级）；</li>
 *   <li>同一类信号 N 分钟内只标首个，默认 {@link SAME_KIND_DEDUP_MINUTES} 分钟；</li>
 *   <li>剔除 09:30 集合竞价那一分钟，但**不整体跳过开盘段**——09:31 起的放量急拉
 *       是全天最重要的信号之一。</li>
 * </ul>
 *
 * <h3>量能基准</h3>
 * <p>用「前 30 分钟中位数、且滞后 10 分钟」的滚动基准：A 股上午量能显著大于下午，
 * 拿全天中位数当基准会让上午误报放量、下午误报缩量；滞后 10 分钟则保证一段放量的
 * 起点一定能被捕捉，放量持续超过 10 分钟后基准自然含入该段、量比回归 1。</p>
 */
import type { IntradayItem, KlineItem } from '@/api/stock'
import { macd } from './stockIndicators.ts'

// ───────────────────────────── 阈值常量 ─────────────────────────────
// 全部阈值集中在此，别在判定逻辑里写裸数字；调用方也可通过 opts 覆盖主要几项。

/** 集合竞价分钟：整根剔除（09:30 那一分钟的价格不是连续竞价结果） */
export const AUCTION_TIME = '09:30'
/** 少于该根数不做分时判定（5 分钟动能 + 滚动基准都需要起步样本） */
export const MIN_SCAN_BARS = 10

/** 放量倍数阈值：当前分钟量 / 滚动基准 ≥ 该值算「放量」 */
export const VOLUME_SURGE_RATIO = 2.5
/** 无量拉升的最大量比（≤ 该值算「无量」） */
export const QUIET_RISE_MAX_RATIO = 0.4
/** 放量急拉 / 放量急跌的 5 分钟动能门槛（%） */
export const SURGE_MOVE_PCT = 0.6
/** 无量拉升的 5 分钟动能门槛（%） */
export const QUIET_RISE_MOVE_PCT = 0.8
/** 动能观察窗口（分钟） */
export const MOMENTUM_BARS = 5

/** 突破 / 破位的放量门槛（倍数） */
export const BREAKOUT_VOLUME_RATIO = 2
/** 突破 / 破位要求「明显越过」关键位的幅度（%）——只穿越不越过会大量误报 */
export const BREAKOUT_MARGIN_PCT = 0.2
/** 突破 / 破位的同向 5 分钟动能门槛（%） */
export const BREAKOUT_MOVE_PCT = 0.5
/** 分时前高 / 前低的回看窗口（分钟） */
export const BREAKOUT_LOOKBACK_BARS = 30
/** 计算前高 / 前低时剔除最近几根，避免「自己和自己比」 */
export const BREAKOUT_EXCLUDE_RECENT_BARS = 4

/** 分时量价背离的最少样本根数 */
export const DIVERGENCE_MIN_BARS = 15
/** 背离判定里「前段极值」的回看窗口（分钟） */
export const DIVERGENCE_LOOKBACK_BARS = 30
/** 背离判定里前段极值剔除最近几根 */
export const DIVERGENCE_EXCLUDE_RECENT_BARS = 5
/** 前 5 分钟均量 / 前段均量 的上限：低于该比例算「量能萎缩」 */
export const DIVERGENCE_VOLUME_SHRINK = 0.6
/** 背离要求的当前量比上限（当前放量就不算动能衰竭） */
export const DIVERGENCE_MAX_VOLUME_RATIO = 1.5
/** 背离要求的新高 / 新低幅度（%） */
export const DIVERGENCE_MIN_EXTREME_PCT = 0.3

/** 同一类信号的去重间隔（分钟） */
export const SAME_KIND_DEDUP_MINUTES = 10

/** 滚动量能基准的回看窗口（分钟） */
export const VOLUME_BASELINE_WINDOW = 30
/** 滚动量能基准的滞后（分钟） */
export const VOLUME_BASELINE_LAG = 10
/** 滚动基准至少要这么多分钟样本，否则退回全局中位数 */
export const VOLUME_BASELINE_MIN_SAMPLES = 5

/** 摆动点（局部峰 / 谷）窗口半径（分钟） */
export const TRAP_SWING_WINDOW = 5
/** 摆动点最小幅度（%）：平台期连续同价既非峰也非谷，靠这个跳过盘整 */
export const TRAP_MIN_SWING_PCT = 0.5
/** 陷阱摆动前后允许的最大跨度（分钟） */
export const TRAP_LOOKBACK_BARS = 40
/** 同一名称陷阱的去重间隔（分钟） */
export const TRAP_SAME_NAME_DEDUP_MINUTES = 15
/** 陷阱里「像样拉升 / 下跌」的最小幅度（%） */
export const TRAP_SWING_RISE_MIN_PCT = 1.2
/** 陷阱里拉升 / 下跌段的放量门槛（倍数） */
export const TRAP_VOLUME_RATIO = 1.5
/** 陷阱里「脉冲放量」的连续窗口（分钟） */
export const TRAP_VOLUME_WINDOW = 5
/** 日 K MACD 背离的最少日 K 根数 */
export const MIN_DAILY_MACD_BARS = 35
/** 日 K 背离在末尾多少根内找摆动点 */
export const DAILY_DIVERGENCE_LOOKBACK = 20
/** 日 K 摆动点窗口半径（根） */
export const DAILY_SWING_WINDOW = 3

// ───────────────────────────── 类型 ─────────────────────────────

/** 分时信号种类 */
export type IntradaySignalKind =
  | 'surge'
  | 'dump'
  | 'quietRise'
  | 'breakout'
  | 'breakdown'
  | 'topDivergence'
  | 'bottomDivergence'

/** 信号强度：info = 提示、warn = 需注意、strong = 强信号 */
export type SignalLevel = 'info' | 'warn' | 'strong'

/** 分时量价信号 */
export interface IntradaySignal {
  /** 在传入的 bars 数组里的下标（不重排、不过滤，便于回指原始数据） */
  barIndex: number
  /** `HH:mm` */
  time: string
  kind: IntradaySignalKind
  level: SignalLevel
  /** 中文短标，可直接做图上的标记文字 */
  label: string
  /** 触发分钟的分时价 */
  price: number
  /** 触发依据（中文，含具体数值） */
  detail: string
}

/** 诱多 / 诱空方向：bull = 疑似诱多（对多头不利）、bear = 疑似诱空 */
export type TrapSide = 'bull' | 'bear'

/**
 * 陷阱强度：1 = 弱、2 = 中、3 = 强。
 *
 * <p>移植时保留了 prh 的两档口径（中 / 强），1 档留作后续更细降级用，
 * 当前不会产出——不要为了「用满三档」去臆造阈值。</p>
 */
export type TrapStrength = 1 | 2 | 3

/** 诱多 / 诱空嫌疑 */
export interface TrapSignal {
  barIndex: number
  time: string
  side: TrapSide
  strength: TrapStrength
  /** 中文短标，带「疑似」前缀，避免被读成结论 */
  label: string
  /** 观测到的客观事实，逐条列出（不含结论） */
  reasons: string[]
  /**
   * 确认条件是否已在盘中兑现。
   *
   * <p>注意语义：它表示「形态破坏这一观测事实已出现」，**不表示**「已确认是诱多」。
   * 诱多 / 诱空成立与否仍要看后续走势，文案里必须保持嫌疑口吻。</p>
   */
  confirmed: boolean
  /** 事实 + 嫌疑口吻的完整说明 */
  detail: string
}

/** 关键位参考价（可选）：昨收 / 日内最高 / 日内最低 */
export interface KeyLevels {
  /** 昨收：突破 / 破位、高开判定的基准 */
  prevClose?: number | null
  /** 日内最高（调用方从行情快照取） */
  dayHigh?: number | null
  /** 日内最低（调用方从行情快照取） */
  dayLow?: number | null
}

/** {@link detectIntradaySignals} 的可选项 */
export interface DetectIntradayOptions {
  keyLevels?: KeyLevels
  /**
   * 日 K。仅用于两件事：① keyLevels.prevClose 缺失时取最后一根收盘兜底；
   * ② 无（分时背离已覆盖）。
   *
   * <p>注意兜底口径：若最后一根是当日**未收盘**的 K 线，兜底值会等于现价，
   * 昨收相关的判定就失真了——这种场景请显式传 keyLevels.prevClose。</p>
   */
  kline?: readonly KlineItem[]
  /** 同类信号去重间隔（分钟），默认 {@link SAME_KIND_DEDUP_MINUTES} */
  dedupMinutes?: number
  /** 是否保留 09:30 集合竞价分钟，默认 false */
  includeAuction?: boolean
  /** 放量倍数阈值，默认 {@link VOLUME_SURGE_RATIO} */
  surgeRatio?: number
  /** 无量拉升的最大量比，默认 {@link QUIET_RISE_MAX_RATIO} */
  quietRiseMaxRatio?: number
}

/** {@link detectTrapSignals} 的可选项 */
export interface DetectTrapOptions {
  keyLevels?: KeyLevels
  kline?: readonly KlineItem[]
  /** 同名陷阱去重间隔（分钟），默认 {@link TRAP_SAME_NAME_DEDUP_MINUTES} */
  dedupMinutes?: number
  /** 是否保留 09:30 集合竞价分钟，默认 false */
  includeAuction?: boolean
}

/** 日 K MACD 背离（无 barIndex：作用于日 K，不落在分时时间轴上） */
export interface DailyDivergence {
  kind: 'topDivergence' | 'bottomDivergence'
  level: SignalLevel
  label: string
  detail: string
  /** 形成背离的两个摆动点日期 `[较早, 较晚]` */
  points: [string, string]
}

// ───────────────────────────── 通用工具 ─────────────────────────────

/** 转成有限正数，否则 0（0 统一表示「该位置没有可用数据」） */
function positive(value: unknown): number {
  const n = Number(value)
  return Number.isFinite(n) && n > 0 ? n : 0
}

function mean(values: readonly number[]): number {
  return values.length === 0 ? 0 : values.reduce((a, b) => a + b, 0) / values.length
}

/** 中位数（忽略 0 / 负值，对开收盘集合竞价的异常量稳健） */
function median(values: readonly number[]): number {
  const sorted = values.filter((v) => v > 0).sort((a, b) => a - b)
  if (sorted.length === 0) return 0
  const mid = Math.floor(sorted.length / 2)
  return sorted.length % 2 === 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2
}

/** a → b 的百分比变动（%） */
function pct(from: number, to: number): number {
  return from > 0 ? ((to - from) / from) * 100 : 0
}

/** [from, to] 闭区间内的最大正值，无数据返回 0 */
function maxPositive(values: readonly number[], from: number, to: number): number {
  const start = Math.max(0, from)
  const end = Math.min(values.length - 1, to)
  let best = 0
  for (let i = start; i <= end; i++) {
    if (values[i] > best) best = values[i]
  }
  return best
}

/** [from, to] 闭区间内的最小正值，无数据返回 0 */
function minPositive(values: readonly number[], from: number, to: number): number {
  const start = Math.max(0, from)
  const end = Math.min(values.length - 1, to)
  let best = 0
  for (let i = start; i <= end; i++) {
    const v = values[i]
    if (v > 0 && (best === 0 || v < best)) best = v
  }
  return best
}

/** [from, to] 闭区间均量（无样本返回 0） */
function meanVolume(volumes: readonly number[], from: number, to: number): number {
  const start = Math.max(0, from)
  const end = Math.min(volumes.length - 1, to)
  if (end < start) return 0
  let sum = 0
  for (let i = start; i <= end; i++) sum += volumes[i]
  return sum / (end - start + 1)
}

/** [from, to] 内任意连续 win 分钟的最大均量（捕捉末尾脉冲放量，不被区间平均稀释） */
function maxWindowMean(
  volumes: readonly number[],
  from: number,
  to: number,
  win: number
): number {
  const start = Math.max(0, from)
  const end = Math.min(volumes.length - 1, to)
  const width = Math.min(win, end - start + 1)
  if (width <= 0) return 0
  let best = 0
  for (let s = start; s <= end - width + 1; s++) {
    let sum = 0
    for (let i = s; i < s + width; i++) sum += volumes[i]
    const m = sum / width
    if (m > best) best = m
  }
  return best
}

/** 已排序索引数组里找 i 之前最近的一个 */
function nearestBefore(indices: readonly number[], i: number): number | null {
  for (let k = indices.length - 1; k >= 0; k--) {
    if (indices[k] < i) return indices[k]
  }
  return null
}

/** 已排序索引数组里找 i 之后最近的一个 */
function nearestAfter(indices: readonly number[], i: number): number | null {
  for (let k = 0; k < indices.length; k++) {
    if (indices[k] > i) return indices[k]
  }
  return null
}

/** 滚动量能基准 + 量比 */
interface VolumeBaseline {
  /** 第 i 分钟的量能基准 */
  baseAt: (i: number) => number
  /** 第 i 分钟的量比（当前量 / 基准） */
  ratioAt: (i: number) => number
}

/**
 * 建滚动量能基准：前 {@link VOLUME_BASELINE_WINDOW} 分钟的中位数，滞后
 * {@link VOLUME_BASELINE_LAG} 分钟；样本不足时退回全局中位数。
 */
function createVolumeBaseline(volumes: readonly number[]): VolumeBaseline {
  const globalBase = median(volumes) || mean(volumes)
  const baseAt = (i: number): number => {
    const from = Math.max(0, i - VOLUME_BASELINE_WINDOW)
    const to = Math.max(0, i - VOLUME_BASELINE_LAG)
    if (to - from < VOLUME_BASELINE_MIN_SAMPLES) return globalBase
    const m = median(volumes.slice(from, to))
    return m > 0 ? m : globalBase
  }
  const ratioAt = (i: number): number => {
    const base = baseAt(i)
    return base > 0 ? volumes[i] / base : 0
  }
  return { baseAt, ratioAt }
}

/** 分时均价：优先 VWAP → 累计均价 → 现价 */
function resolveAveragePrices(bars: readonly IntradayItem[], prices: readonly number[]): number[] {
  return bars.map((bar, i) => {
    const vwap = positive(bar?.vwap)
    if (vwap > 0) return vwap
    const avg = positive(bar?.avgPrice)
    if (avg > 0) return avg
    return prices[i]
  })
}

/** 昨收：opts 显式值优先，其次日内 K 最后一根收盘 */
function resolvePrevClose(opts: { keyLevels?: KeyLevels; kline?: readonly KlineItem[] }): number {
  const explicit = positive(opts.keyLevels?.prevClose)
  if (explicit > 0) return explicit
  const kline = opts.kline
  if (!Array.isArray(kline)) return 0
  for (let i = kline.length - 1; i >= 0; i--) {
    const close = positive(kline[i]?.close)
    if (close > 0) return close
  }
  return 0
}

/** 取分时数组的基础量：prices / volumes / times，非法值归 0 但**不删元素**（barIndex 必须对得上原始数组） */
function prepareBars(bars: readonly IntradayItem[]): {
  len: number
  prices: number[]
  volumes: number[]
  times: string[]
} {
  const list = Array.isArray(bars) ? bars : []
  const prices: number[] = []
  const volumes: number[] = []
  const times: string[] = []
  for (const bar of list) {
    prices.push(positive(bar?.price))
    const vol = Number(bar?.volume)
    volumes.push(Number.isFinite(vol) && vol > 0 ? vol : 0)
    times.push(typeof bar?.time === 'string' ? bar.time : '')
  }
  return { len: list.length, prices, volumes, times }
}

const KIND_LABEL: Record<IntradaySignalKind, string> = {
  surge: '放量急拉',
  dump: '放量急跌',
  quietRise: '无量拉升',
  breakout: '突破',
  breakdown: '破位',
  topDivergence: '顶背离',
  bottomDivergence: '底背离'
}

// ───────────────────────────── 分时量价信号 ─────────────────────────────

/** 突破 / 破位用的候选关键位 */
interface CandidateLevel {
  price: number
  /** 中文名，进 detail */
  label: string
  /** 是否属于「外生关键位」（昨收 / 日内高低），是则强度升到 strong */
  strong: boolean
}

/**
 * 识别分时量价信号。
 *
 * <p>判定顺序即优先级：前瞻预警（放量急拉 / 急跌 / 无量拉升）→ 突破 / 破位 → 背离。
 * 同一分钟只会留下优先级最高的那一个。</p>
 *
 * @param bars 当日分时（按时间升序，`HH:mm`）
 * @param opts 关键位、阈值、去重间隔等，见 {@link DetectIntradayOptions}
 * @returns 按 barIndex 升序排列的信号数组；数据不足或无成交量时返回空数组
 */
export function detectIntradaySignals(
  bars: readonly IntradayItem[],
  opts: DetectIntradayOptions = {}
): IntradaySignal[] {
  const { len, prices, volumes, times } = prepareBars(bars)
  if (len < MIN_SCAN_BARS) return []
  if (prices.filter((p) => p > 0).length < MIN_SCAN_BARS) return []
  // 完全没有量能数据时做不了量价判断——宁可不出信号，也不要瞎报
  if (mean(volumes) <= 0) return []

  const baseline = createVolumeBaseline(volumes)
  const dedupMinutes = positive(opts.dedupMinutes) || SAME_KIND_DEDUP_MINUTES
  const surgeRatio = positive(opts.surgeRatio) || VOLUME_SURGE_RATIO
  const quietRiseRatio = positive(opts.quietRiseMaxRatio) || QUIET_RISE_MAX_RATIO
  const includeAuction = opts.includeAuction === true
  const prevClose = resolvePrevClose(opts)

  // 关键位：昨收 / 日内高低。突破与破位都需要一个「参考价」，否则只能跟前高前低比。
  const upLevels: CandidateLevel[] = []
  const downLevels: CandidateLevel[] = []
  if (prevClose > 0) {
    upLevels.push({ price: prevClose, label: '昨收', strong: true })
    downLevels.push({ price: prevClose, label: '昨收', strong: true })
  }
  const dayHigh = positive(opts.keyLevels?.dayHigh)
  if (dayHigh > 0) upLevels.push({ price: dayHigh, label: '日内高', strong: true })
  const dayLow = positive(opts.keyLevels?.dayLow)
  if (dayLow > 0) downLevels.push({ price: dayLow, label: '日内低', strong: true })

  const out: IntradaySignal[] = []
  const usedMinutes = new Set<number>()
  const lastMinuteOfKind = new Map<IntradaySignalKind, number>()

  const push = (
    i: number,
    kind: IntradaySignalKind,
    level: SignalLevel,
    detail: string
  ): boolean => {
    if (i < 1 || i >= len) return false
    if (!includeAuction && times[i] === AUCTION_TIME) return false // 剔除集合竞价分钟
    if (prices[i] <= 0) return false
    if (usedMinutes.has(i)) return false // 同一分钟只留一个
    const last = lastMinuteOfKind.get(kind)
    if (last != null && i - last < dedupMinutes) return false // 同类 N 分钟内只标首个
    usedMinutes.add(i)
    lastMinuteOfKind.set(kind, i)
    out.push({
      barIndex: i,
      time: times[i],
      kind,
      level,
      label: KIND_LABEL[kind],
      price: prices[i],
      detail
    })
    return true
  }

  const margin = 1 + BREAKOUT_MARGIN_PCT / 100

  for (let i = 1; i < len; i++) {
    if (prices[i] <= 0) continue
    if (!includeAuction && times[i] === AUCTION_TIME) continue // 集合竞价分钟整根跳过

    const chg = pct(prices[Math.max(0, i - MOMENTUM_BARS)], prices[i])
    const ratio = baseline.ratioAt(i)
    const high5 = maxPositive(prices, i - MOMENTUM_BARS, i)
    const low5 = minPositive(prices, i - MOMENTUM_BARS, i)

    // ① 前瞻预警：当下可判、不依赖后续走势，实盘反应点
    if (ratio >= surgeRatio && chg >= SURGE_MOVE_PCT) {
      if (
        push(
          i,
          'surge',
          'warn',
          `${times[i]} 起 5 分钟急拉 ${chg.toFixed(1)}%（量 ${ratio.toFixed(1)}× 基准），追高风险——警惕冲高回落`
        )
      ) {
        continue
      }
    } else if (ratio >= surgeRatio && chg <= -SURGE_MOVE_PCT) {
      if (
        push(
          i,
          'dump',
          'warn',
          `${times[i]} 起 5 分钟急跌 ${(-chg).toFixed(1)}%（量 ${ratio.toFixed(1)}× 基准），恐慌盘涌出——勿低位割肉`
        )
      ) {
        continue
      }
    } else if (ratio <= quietRiseRatio && chg >= QUIET_RISE_MOVE_PCT) {
      if (
        push(
          i,
          'quietRise',
          'warn',
          `${times[i]} 起 5 分钟拉升 ${chg.toFixed(1)}% 但量仅 ${ratio.toFixed(1)}× 基准，无承接的虚涨`
        )
      ) {
        continue
      }
    }

    // ② 放量突破 / 破位：先跟前高前低比（prh 原口径），再看外生关键位
    if (i >= BREAKOUT_LOOKBACK_BARS && ratio >= BREAKOUT_VOLUME_RATIO) {
      const priorHigh = maxPositive(
        prices,
        i - BREAKOUT_LOOKBACK_BARS,
        i - BREAKOUT_EXCLUDE_RECENT_BARS
      )
      if (priorHigh > 0 && prices[i] > priorHigh * margin && chg >= BREAKOUT_MOVE_PCT) {
        if (
          push(
            i,
            'breakout',
            'warn',
            `${times[i]} 放量突破 ${BREAKOUT_LOOKBACK_BARS} 分钟前高 ${priorHigh.toFixed(2)}（+${pct(priorHigh, prices[i]).toFixed(1)}%，量 ${ratio.toFixed(1)}× 基准）`
          )
        ) {
          continue
        }
      }
      const priorLow = minPositive(
        prices,
        i - BREAKOUT_LOOKBACK_BARS,
        i - BREAKOUT_EXCLUDE_RECENT_BARS
      )
      if (priorLow > 0 && prices[i] < priorLow * (2 - margin) && chg <= -BREAKOUT_MOVE_PCT) {
        if (
          push(
            i,
            'breakdown',
            'warn',
            `${times[i]} 放量跌破 ${BREAKOUT_LOOKBACK_BARS} 分钟前低 ${priorLow.toFixed(2)}（${pct(priorLow, prices[i]).toFixed(1)}%，量 ${ratio.toFixed(1)}× 基准）`
          )
        ) {
          continue
        }
      }
    }

    // ③ 外生关键位：只认「穿越」那一分钟（上一分钟还没越过、这一分钟越过了）
    if (ratio >= BREAKOUT_VOLUME_RATIO && chg >= BREAKOUT_MOVE_PCT) {
      const crossed = upLevels
        .filter((lv) => prices[i - 1] <= lv.price && prices[i] > lv.price * margin)
        .sort((a, b) => b.price - a.price)[0]
      if (crossed) {
        if (
          push(
            i,
            'breakout',
            crossed.strong ? 'strong' : 'warn',
            `${times[i]} 放量站上${crossed.label} ${crossed.price.toFixed(2)}（+${pct(crossed.price, prices[i]).toFixed(1)}%，量 ${ratio.toFixed(1)}× 基准）`
          )
        ) {
          continue
        }
      }
    }
    if (ratio >= BREAKOUT_VOLUME_RATIO && chg <= -BREAKOUT_MOVE_PCT) {
      const crossed = downLevels
        .filter((lv) => prices[i - 1] >= lv.price && prices[i] < lv.price * (2 - margin))
        .sort((a, b) => a.price - b.price)[0]
      if (crossed) {
        if (
          push(
            i,
            'breakdown',
            crossed.strong ? 'strong' : 'warn',
            `${times[i]} 放量跌破${crossed.label} ${crossed.price.toFixed(2)}（${pct(crossed.price, prices[i]).toFixed(1)}%，量 ${ratio.toFixed(1)}× 基准）`
          )
        ) {
          continue
        }
      }
    }

    // ④ 分时量价背离：显著新高 / 新低 + 量能明显萎缩 + 当前非放量
    if (i >= DIVERGENCE_MIN_BARS) {
      const shrinkGuard = ratio <= DIVERGENCE_MAX_VOLUME_RATIO
      const recentVol = meanVolume(volumes, i - (MOMENTUM_BARS - 1), i)
      const priorVol = meanVolume(
        volumes,
        i - (MOMENTUM_BARS + DIVERGENCE_EXCLUDE_RECENT_BARS - 1),
        i - DIVERGENCE_EXCLUDE_RECENT_BARS
      )
      const shrunk = priorVol > 0 && recentVol < priorVol * DIVERGENCE_VOLUME_SHRINK

      if (shrunk && shrinkGuard) {
        const priorHigh = maxPositive(
          prices,
          i - DIVERGENCE_LOOKBACK_BARS,
          i - DIVERGENCE_EXCLUDE_RECENT_BARS
        )
        const isLocalHigh =
          high5 > 0 &&
          prices[i] >= high5 &&
          priorHigh > 0 &&
          prices[i] > priorHigh * (1 + DIVERGENCE_MIN_EXTREME_PCT / 100)
        if (isLocalHigh) {
          if (
            push(
              i,
              'topDivergence',
              'info',
              `${times[i]} 创 ${DIVERGENCE_LOOKBACK_BARS} 分钟新高（+${pct(priorHigh, prices[i]).toFixed(1)}%）但量能萎缩（前 ${MOMENTUM_BARS} 分钟 ${(recentVol / (baseline.baseAt(i) || 1)).toFixed(1)}× vs 前段 ${(priorVol / (baseline.baseAt(i) || 1)).toFixed(1)}×），上涨动能不足`
            )
          ) {
            continue
          }
        }
        const priorLow = minPositive(
          prices,
          i - DIVERGENCE_LOOKBACK_BARS,
          i - DIVERGENCE_EXCLUDE_RECENT_BARS
        )
        const isLocalLow =
          low5 > 0 &&
          prices[i] <= low5 &&
          priorLow > 0 &&
          prices[i] < priorLow * (1 - DIVERGENCE_MIN_EXTREME_PCT / 100)
        if (isLocalLow) {
          push(
            i,
            'bottomDivergence',
            'info',
            `${times[i]} 创 ${DIVERGENCE_LOOKBACK_BARS} 分钟新低（${pct(priorLow, prices[i]).toFixed(1)}%）但量能萎缩（前 ${MOMENTUM_BARS} 分钟 ${(recentVol / (baseline.baseAt(i) || 1)).toFixed(1)}× vs 前段 ${(priorVol / (baseline.baseAt(i) || 1)).toFixed(1)}×），抛压衰竭`
          )
        }
      }
    }
  }

  return out
}

// ───────────────────────────── 诱多 / 诱空嫌疑 ─────────────────────────────

/** 内部草稿：确认条件、事实列表、结论句分开写，便于组装成嫌疑口吻的 detail */
interface TrapDraft {
  minute: number
  side: TrapSide
  strength: TrapStrength
  label: string
  reasons: string[]
  confirmed: boolean
  /** 嫌疑口吻的结论句（不得写成确定性结论） */
  summary: string
}

/**
 * 识别分时量价陷阱（诱多 / 诱空）的**嫌疑**。
 *
 * <p>「诱」是前瞻性判断：陷阱是否成立需要后续走势确认。因此每条结果都带
 * {@link TrapSignal.reasons}（客观事实）与 {@link TrapSignal.confirmed}
 * （确认条件是否已在盘中兑现），文案一律用「疑似」口吻，**不输出确定性结论**。</p>
 *
 * <h3>检测项（与 prh 一致）</h3>
 * <ul>
 *   <li>诱多：放量冲高回落、高位放量滞涨、尾盘无量急拉、尾盘高位放量砸盘、高开冲高破均价；</li>
 *   <li>诱空：放量急跌后收复、尾盘低位放量急砸、低位放量反转。</li>
 * </ul>
 *
 * <p>反后视镜：不等「回落 ≥50%」这类事后确认，而是找峰后第一个跌破启动位 / 均价的分钟
 * 当时间戳——跌破当下即可判定，盘中就能看见。</p>
 *
 * @param bars 当日分时（`HH:mm` 升序）
 * @param opts 关键位（昨收用于高开判定）、去重间隔
 */
export function detectTrapSignals(
  bars: readonly IntradayItem[],
  opts: DetectTrapOptions = {}
): TrapSignal[] {
  const { len, prices, volumes, times } = prepareBars(bars)
  if (len < MIN_SCAN_BARS) return []
  if (prices.filter((p) => p > 0).length < MIN_SCAN_BARS) return []
  if (mean(volumes) <= 0) return []

  const list = Array.isArray(bars) ? bars : []
  const avgPrices = resolveAveragePrices(list, prices)
  const baseline = createVolumeBaseline(volumes)
  const dedupMinutes = positive(opts.dedupMinutes) || TRAP_SAME_NAME_DEDUP_MINUTES
  const includeAuction = opts.includeAuction === true
  const prevClose = resolvePrevClose(opts)

  /** 截至 i 的前 w 分钟均量 / 滚动基准 */
  const volumeRatio = (i: number, w: number): number => {
    const from = Math.max(0, i - w + 1)
    const m = meanVolume(volumes, from, i)
    const base = baseline.baseAt(i)
    return base > 0 ? m / base : 1
  }

  // 局部极值（带幅度约束）：窗口 ±W，突出度 ≥ TRAP_MIN_SWING_PCT 才算有效摆动点。
  // 平台期连续同价既非峰也非谷，纯严格比较会把摆动起点丢掉，所以用突出度天然跳过盘整。
  const swingWindow = TRAP_SWING_WINDOW
  const peaks: number[] = []
  const valleys: number[] = []
  for (let i = swingWindow; i < len - swingWindow; i++) {
    const center = prices[i]
    if (center <= 0) continue
    let isMax = true
    let isMin = true
    for (let j = i - swingWindow; j <= i + swingWindow; j++) {
      if (j === i || prices[j] <= 0) continue
      if (prices[j] > center) isMax = false
      if (prices[j] < center) isMin = false
    }
    if (isMax) {
      const lo = minPositive(prices, i - swingWindow, i + swingWindow)
      if (lo > 0 && ((center - lo) / center) * 100 >= TRAP_MIN_SWING_PCT) peaks.push(i)
    }
    if (isMin) {
      const hi = maxPositive(prices, i - swingWindow, i + swingWindow)
      if (hi > 0 && ((hi - center) / center) * 100 >= TRAP_MIN_SWING_PCT) valleys.push(i)
    }
  }

  const out: TrapSignal[] = []
  const usedMinutes = new Set<number>()
  const lastMinuteByLabel = new Map<string, number>()

  const add = (draft: TrapDraft): boolean => {
    if (draft.minute < 1 || draft.minute >= len) return false
    if (!includeAuction && times[draft.minute] === AUCTION_TIME) return false
    if (usedMinutes.has(draft.minute)) return false // 同一分钟只留一个
    const last = lastMinuteByLabel.get(draft.label)
    if (last != null && draft.minute - last < dedupMinutes) return false // 同名 N 分钟内去重
    usedMinutes.add(draft.minute)
    lastMinuteByLabel.set(draft.label, draft.minute)
    out.push({
      barIndex: draft.minute,
      time: times[draft.minute],
      side: draft.side,
      strength: draft.strength,
      label: draft.label,
      reasons: draft.reasons,
      confirmed: draft.confirmed,
      detail: `${draft.reasons.join('；')}。${draft.summary}`
    })
    return true
  }

  const intradayHigh = maxPositive(prices, 0, len - 1)
  const intradayLow = minPositive(prices, 0, len - 1)
  const highIdx = intradayHigh > 0 ? prices.indexOf(intradayHigh) : -1
  const lowIdx = intradayLow > 0 ? prices.indexOf(intradayLow) : -1

  // ── A. 放量冲高回落（诱多）──
  for (const peak of peaks) {
    if (peak < 6 || peak > len - 8) continue
    const before = nearestBefore(valleys, peak)
    const after = nearestAfter(valleys, peak)
    if (before == null || after == null) continue
    if (peak - before > TRAP_LOOKBACK_BARS || after - peak > TRAP_LOOKBACK_BARS) continue
    const rise = pct(prices[before], prices[peak])
    if (rise < TRAP_SWING_RISE_MIN_PCT) continue
    const volRise = maxWindowMean(volumes, before, peak, TRAP_VOLUME_WINDOW) / baseline.baseAt(peak)
    if (!(volRise >= TRAP_VOLUME_RATIO)) continue
    let broke = -1
    const limit = Math.min(after, len - 1)
    for (let i = peak + 1; i <= limit; i++) {
      if (prices[i] <= 0) continue
      if (prices[i] < prices[before] || (avgPrices[i] > 0 && prices[i] < avgPrices[i])) {
        broke = i
        break
      }
    }
    if (broke < 0) continue
    const belowStart = prices[broke] < prices[before]
    add({
      minute: broke,
      side: 'bull',
      strength: rise >= 2.5 && volRise >= 2 ? 3 : 2,
      label: '疑似诱多·放量冲高回落',
      reasons: [
        `${times[peak]} 前放量拉升 ${rise.toFixed(1)}%（量 ${volRise.toFixed(1)}× 基准，自 ${times[before]} ${prices[before].toFixed(2)} 起）`,
        `${times[broke]} 跌破${belowStart ? `启动位 ${prices[before].toFixed(2)}` : `均价 ${avgPrices[broke].toFixed(2)}`}`
      ],
      confirmed: true,
      summary: '冲高买入者已被套，诱多嫌疑的确认条件（跌破启动位 / 均价）已兑现，但仍需后续走势验证'
    })
  }

  // ── B. 放量急跌后收复（诱空）──
  for (const valley of valleys) {
    if (valley < 6 || valley > len - 8) continue
    const before = nearestBefore(peaks, valley)
    const after = nearestAfter(peaks, valley)
    if (before == null || after == null) continue
    if (valley - before > TRAP_LOOKBACK_BARS || after - valley > TRAP_LOOKBACK_BARS) continue
    const fall = pct(prices[before], prices[valley])
    if (fall > -TRAP_SWING_RISE_MIN_PCT) continue
    const volFall =
      maxWindowMean(volumes, before, valley, TRAP_VOLUME_WINDOW) / baseline.baseAt(valley)
    if (!(volFall >= TRAP_VOLUME_RATIO)) continue
    let recovered = -1
    const limit = Math.min(after, len - 1)
    for (let i = valley + 1; i <= limit; i++) {
      if (prices[i] <= 0) continue
      if (prices[i] > prices[before] || (avgPrices[i] > 0 && prices[i] > avgPrices[i])) {
        recovered = i
        break
      }
    }
    if (recovered < 0) continue
    const aboveStart = prices[recovered] > prices[before]
    add({
      minute: recovered,
      side: 'bear',
      strength: -fall >= 2.5 && volFall >= 2 ? 3 : 2,
      label: '疑似诱空·放量急跌后收复',
      reasons: [
        `${times[before]} 起 ${valley - before} 分钟内放量下跌 ${fall.toFixed(1)}%（量 ${volFall.toFixed(1)}× 基准）`,
        `${times[recovered]} 收复${aboveStart ? `启动位 ${prices[before].toFixed(2)}` : `均价 ${avgPrices[recovered].toFixed(2)}`}`
      ],
      confirmed: true,
      summary: '恐慌卖出者已踏空，诱空嫌疑的确认条件（收复启动位 / 均价）已兑现，但仍需后续走势验证'
    })
  }

  // ── C. 高位放量滞涨（诱多）──
  if (highIdx > len * 0.2 && highIdx < len - 3) {
    const last = len - 1
    const distHigh = pct(prices[highIdx], prices[last])
    const distAvg = avgPrices[last] > 0 ? pct(avgPrices[last], prices[last]) : 0
    const chg5 = pct(prices[Math.max(0, last - 4)], prices[last])
    const vol5 = volumeRatio(last, 5)
    if (distHigh > -0.8 && distAvg > 1.2 && vol5 >= 1.8 && Math.abs(chg5) <= 0.3) {
      add({
        minute: last,
        side: 'bull',
        strength: 2,
        label: '疑似诱多·高位放量滞涨',
        reasons: [
          `价格维持日内高位（距最高 ${distHigh.toFixed(1)}%）`,
          `近 5 分钟量能达基准 ${vol5.toFixed(1)} 倍但价格几乎不动（${chg5.toFixed(2)}%）`
        ],
        confirmed: false,
        summary: `量价背离、出货嫌疑：确认条件是跌破均价 ${avgPrices[last].toFixed(2)}，当前尚未跌破`
      })
    }
  }

  // ── D. 尾盘量价检查（滚动窗口，快照式实时预警，不等收盘）──
  if (len >= 30) {
    const tailStart = Math.max(15, len - 30)
    for (let i = tailStart + 14; i < len; i++) {
      const start = i - 14
      const move15 = pct(prices[start], prices[i])
      const vol15 = volumeRatio(i, 15)

      // D1. 无量急拉（诱多）
      if (move15 >= 1.2 && vol15 <= 0.6) {
        add({
          minute: i,
          side: 'bull',
          strength: 2,
          label: '疑似诱多·尾盘无量急拉',
          reasons: [
            `${times[start]}-${times[i]} 拉升 ${move15.toFixed(1)}%`,
            `量能仅为基准 ${vol15.toFixed(1)} 倍，无资金承接`
          ],
          confirmed: false,
          summary: '虚拉嫌疑：确认条件是跌破拉升起点或次日开盘走弱，当前尚未满足'
        })
      }

      // D2. 放量急砸：低位砸 = 诱空 / 恐慌；高位砸 = 出货（对多头不利）
      if (move15 <= -1 && vol15 >= 1.5) {
        const nearLow = intradayLow > 0 && prices[i] <= intradayLow * 1.008
        if (nearLow) {
          add({
            minute: i,
            side: 'bear',
            strength: 2,
            label: '疑似诱空·尾盘低位放量急砸',
            reasons: [
              `${times[start]}-${times[i]} 下跌 ${(-move15).toFixed(1)}%`,
              `量能达基准 ${vol15.toFixed(1)} 倍且价格处于日内低位`
            ],
            confirmed: false,
            summary: '杀跌盘集中涌出的恐慌嫌疑：确认条件是次日高开或盘中快速反弹站上均价'
          })
        } else {
          add({
            minute: i,
            side: 'bull',
            strength: 2,
            label: '疑似诱多·尾盘高位放量砸盘',
            reasons: [
              `${times[start]}-${times[i]} 下跌 ${(-move15).toFixed(1)}% 且放量（${vol15.toFixed(1)} 倍基准）`,
              '价格仍在日内中高位'
            ],
            confirmed: false,
            summary: '高位出货嫌疑：确认条件是跌破日内均价或前低，追高需谨慎'
          })
        }
      }
    }
  }

  // ── E. 高开冲高破均价（诱多）──
  if (prevClose > 0 && len > 30) {
    const open = prices[0]
    const gap = pct(prevClose, open)
    if (gap >= 1.5) {
      const peak = maxPositive(prices, 0, Math.min(30, len) - 1)
      const peakIdx = peak > 0 ? prices.indexOf(peak) : -1
      if (peakIdx >= 5 && pct(open, peak) >= 1) {
        let broke = -1
        for (let i = peakIdx + 1; i < len; i++) {
          if (avgPrices[i] > 0 && prices[i] > 0 && prices[i] < avgPrices[i]) {
            broke = i
            break
          }
        }
        if (broke > 0) {
          add({
            minute: broke,
            side: 'bull',
            strength: gap >= 3 ? 3 : 2,
            label: '疑似诱多·高开冲高破均价',
            reasons: [
              `高开 ${gap.toFixed(1)}%，冲高 ${pct(open, peak).toFixed(1)}% 后于 ${times[broke]} 跌破均价`,
              `开盘价 ${open.toFixed(2)}，日内高点 ${peak.toFixed(2)}`
            ],
            confirmed: true,
            summary: '开盘追高者被套，诱多嫌疑的确认条件（跌破均价）已兑现，但仍需后续走势验证'
          })
        }
      }
    }
  }

  // ── F. 低位放量反转（诱空）──
  if (lowIdx > len * 0.25 && lowIdx < len - 5) {
    const last = len - 1
    const recovered = avgPrices[last] > 0 && prices[last] > avgPrices[last]
    const vol10 = volumeRatio(last, 10)
    const riseFromLow = pct(prices[lowIdx], prices[last])
    if (recovered && vol10 >= 1.8 && riseFromLow >= 1) {
      add({
        minute: lowIdx,
        side: 'bear',
        strength: 2,
        label: '疑似诱空·低位放量反转',
        reasons: [
          `日内低点 ${times[lowIdx]} 后放量（近 10 分钟 ${vol10.toFixed(1)} 倍基准）拉升 ${riseFromLow.toFixed(1)}%`,
          '价格重新站上均价'
        ],
        confirmed: true,
        summary: '早盘割肉者已踏空，诱空嫌疑的确认条件（收复均价）已兑现，但仍需后续走势验证'
      })
    }
  }

  return out.sort((a, b) => a.barIndex - b.barIndex)
}

// ───────────────────────────── 日 K MACD 背离 ─────────────────────────────

/** 找局部峰值：值大于左右各 window 个邻居 */
function findLocalPeaks(values: readonly number[], from: number, to: number, window: number): number[] {
  const peaks: number[] = []
  for (let i = from + window; i <= to - window; i++) {
    let isPeak = true
    for (let j = i - window; j <= i + window; j++) {
      if (j !== i && values[j] >= values[i]) {
        isPeak = false
        break
      }
    }
    if (isPeak) peaks.push(i)
  }
  return peaks
}

/** 找局部谷值：值小于左右各 window 个邻居 */
function findLocalValleys(
  values: readonly number[],
  from: number,
  to: number,
  window: number
): number[] {
  const valleys: number[] = []
  for (let i = from + window; i <= to - window; i++) {
    let isValley = true
    for (let j = i - window; j <= i + window; j++) {
      if (j !== i && values[j] <= values[i]) {
        isValley = false
        break
      }
    }
    if (isValley) valleys.push(i)
  }
  return valleys
}

/**
 * 日 K MACD 背离（移植 prh useT0Signals 的日线背离判定）。
 *
 * <p>与分时量价背离（{@link detectIntradaySignals} 的顶 / 底背离）不是一回事：
 * 这里比的是「价格新高但 DIF 没跟上」，用的是日 K 收盘序列，日 K 不足
 * {@link MIN_DAILY_MACD_BARS} 根时直接返回空数组，绝不拿半截数据下结论。</p>
 *
 * @param kline 日 K（按日期升序）
 * @returns 至多 2 条（顶背离 + 底背离）
 */
export function detectDailyDivergence(kline: readonly KlineItem[]): DailyDivergence[] {
  const list = (Array.isArray(kline) ? kline : []).filter((bar) => positive(bar?.close) > 0)
  if (list.length < MIN_DAILY_MACD_BARS) return []

  const closes = list.map((bar) => positive(bar.close))
  const highs = list.map((bar) => positive(bar.high) || positive(bar.close))
  const lows = list.map((bar) => positive(bar.low) || positive(bar.close))
  const dif = macd(closes).dif
  const last = list.length - 1
  const lookback = Math.min(DAILY_DIVERGENCE_LOOKBACK, list.length - 1)
  const start = list.length - lookback
  const out: DailyDivergence[] = []

  const peaks = findLocalPeaks(highs, start, last, DAILY_SWING_WINDOW)
  if (peaks.length >= 2) {
    const first = peaks[peaks.length - 2]
    const second = peaks[peaks.length - 1]
    if (highs[second] > highs[first] && dif[second] < dif[first]) {
      out.push({
        kind: 'topDivergence',
        level: 'info',
        label: '日K MACD 顶背离',
        detail: `${list[first].date} 与 ${list[second].date} 两个高点抬高（${highs[first].toFixed(2)} → ${highs[second].toFixed(2)}），但 MACD DIF 走低（${dif[first].toFixed(3)} → ${dif[second].toFixed(3)}），上涨动能减弱`,
        points: [String(list[first].date), String(list[second].date)]
      })
    }
  }

  const valleys = findLocalValleys(lows, start, last, DAILY_SWING_WINDOW)
  if (valleys.length >= 2) {
    const first = valleys[valleys.length - 2]
    const second = valleys[valleys.length - 1]
    if (lows[second] < lows[first] && dif[second] > dif[first]) {
      out.push({
        kind: 'bottomDivergence',
        level: 'info',
        label: '日K MACD 底背离',
        detail: `${list[first].date} 与 ${list[second].date} 两个低点下移（${lows[first].toFixed(2)} → ${lows[second].toFixed(2)}），但 MACD DIF 抬升（${dif[first].toFixed(3)} → ${dif[second].toFixed(3)}），下跌动能减弱`,
        points: [String(list[first].date), String(list[second].date)]
      })
    }
  }

  return out
}

// ═══════════════════════════ 分时图标记（computeT0Signals） ═══════════════════════════
//
// 上面三组 detect* 是桌面版口径的完整移植（5 分钟动能 + 滚动中位数基准 + 日 K 背离），
// 服务于信号历史与回测；下面这组是**分时图专用的精简口径**，只产出「能画在图上的点」：
// 判定更贴近单分钟行为（上一分钟价 → 这一分钟价），阈值全部可在调用处覆盖。
// 两套并存是刻意的——同一份数据要同时喂给「历史列表」和「图上标记」两种消费方，
// 强行合并会让任一侧的口径被迫妥协。

/** 分时图标记种类。只有 7 种：背离需要日 K，不属于「日内」范畴，不出现在分时图上。 */
export interface T0Marker {
  /** 标记所在分钟的 time（与 IntradayItem.time 同格式），用于在分时图上定位 */
  time: string
  /** 价格位置：标记画在该分钟的价格上 */
  price: number
  kind: 'spike-up' | 'spike-down' | 'dry-rally' | 'break-up' | 'break-down' | 'trap-bull' | 'trap-bear'
  /** 人看的短标签，如「放量急拉」「突破」 */
  label: string
  /** 图标字符，如 '⚡' '⚠️' '♪' '▲' '▼' '🪤' */
  icon: string
  /** 强度，只有 trap-* 有值 */
  strength?: 'weak' | 'medium' | 'strong'
  /** 一句话说明，可用于 tooltip */
  detail: string
}

/** 阈值覆盖项，缺省值见下方的 T0_* 常量 */
export interface T0SignalOptions {
  volumeSpikeRatio?: number
  surgePct?: number
  dryRatio?: number
  /** 均量回看窗口，默认 20 分钟 */
  volumeWindow?: number
}

/** 放量倍数默认值：当前分钟量 ≥ 近 N 分钟均量的 3 倍才算放量 */
const T0_SPIKE_RATIO = 3
/** 单分钟涨跌门槛（%）。用「上一分钟 → 这一分钟」而不是 5 分钟动能，
 *  是因为画在图上的是**这一分钟**，读者对照的是相邻两根柱子 */
const T0_SURGE_PCT = 0.5
/** 无量拉升上限：量能不到均量的 0.6 倍却还在涨，说明拉升没有承接 */
const T0_DRY_RATIO = 0.6
/** 均量回看窗口（分钟） */
const T0_VOLUME_WINDOW = 20

/** 集合竞价分钟。09:30 那一根是集合竞价撮合结果：价格是开盘价、量是一次性撮合量，
 *  两者都不属于连续竞价，留着它会同时污染「上一分钟价」和「均量」两个分母 */
const T0_AUCTION_MINUTE = 9 * 60 + 30
/** 量能判定起点 09:35。剔除竞价后只剩 09:31~09:34 四根，量比的分母至少要几根样本才稳定 */
const T0_VOLUME_START_MINUTE = 9 * 60 + 35
/** 突破 / 破位起点 10:00（开盘后 30 分钟）。开盘半小时几乎每分钟都在刷新当日高低，
 *  此时判突破等于把噪点当信号，所以前 30 分钟一律不判 */
const T0_BREAK_START_MINUTE = 9 * 60 + 30 + 30
/** 同类信号去重窗口（分钟）。一波拉升能连续刷出七八个同向分钟，
 *  不去重就会把整张图糊满标记，反而看不见真正的起点 */
const T0_SAME_KIND_DEDUP_MINUTES = 10
/** 诱多/诱空条件①：新高（新低）当根量能低于均量的该倍数，即「创新高却没有量」 */
const T0_TRAP_VOLUME_RATIO = 0.7
/** 诱多/诱空条件②：急拉（急跌）后回吐该比例以上，算打回原形 */
const T0_TRAP_RETRACE_RATIO = 0.5
/** 诱多/诱空条件②：急拉（急跌）之后的有效观察窗口（分钟） */
const T0_TRAP_RETRACE_WINDOW = 10

/** 读取一个可选的正数阈值，非法值退回默认 */
function t0Number(value: unknown, fallback: number): number {
  const n = Number(value)
  return Number.isFinite(n) && n > 0 ? n : fallback
}

/** `HH:mm` → 当日第几分钟；解析失败返回 -1（后续用 `<= T0_AUCTION_MINUTE` 一并挡掉） */
function t0Minute(time: unknown): number {
  if (typeof time !== 'string') return -1
  const parts = time.split(':')
  if (parts.length !== 2) return -1
  const h = Number(parts[0])
  const m = Number(parts[1])
  if (!Number.isFinite(h) || !Number.isFinite(m)) return -1
  return h * 60 + m
}

/** [from, to] 闭区间最大正价，无数据返回 0 */
function t0Max(prices: readonly number[], from: number, to: number): number {
  const start = Math.max(0, from)
  const end = Math.min(prices.length - 1, to)
  let best = 0
  for (let i = start; i <= end; i++) {
    if (prices[i] > best) best = prices[i]
  }
  return best
}

/** [from, to] 闭区间最小正价，无数据返回 0 */
function t0Min(prices: readonly number[], from: number, to: number): number {
  const start = Math.max(0, from)
  const end = Math.min(prices.length - 1, to)
  let best = 0
  for (let i = start; i <= end; i++) {
    const v = prices[i]
    if (v > 0 && (best === 0 || v < best)) best = v
  }
  return best
}

/** 第 i 根之前 window 根的均量（不含第 i 根，且剔除集合竞价分钟与 0 量） */
function t0WindowMean(
  volumes: readonly number[],
  minutes: readonly number[],
  i: number,
  window: number
): number {
  const from = Math.max(0, i - window)
  let sum = 0
  let count = 0
  for (let k = from; k < i; k++) {
    if (minutes[k] <= T0_AUCTION_MINUTE) continue
    const v = volumes[k]
    if (v > 0) {
      sum += v
      count++
    }
  }
  return count > 0 ? sum / count : 0
}

/** 当日累计均价：优先用成交额累计（turnover 的单位不影响比值），缺失时退回「价 × 量」近似。
 *  分时接口里的 avgPrice 才是权威，但本函数的入参只有 turnover，只能这样近似 */
function t0CumulativeAverage(
  prices: readonly number[],
  volumes: readonly number[],
  turnovers: readonly number[],
  i: number
): number {
  let amount = 0
  let volume = 0
  for (let k = 0; k <= i; k++) {
    const v = volumes[k]
    if (v <= 0) continue
    const t = turnovers[k]
    amount += t > 0 ? t : prices[k] * v
    volume += v
  }
  return volume > 0 ? amount / volume : prices[i]
}

/** 每个标记种类的中文短名，summarizeMarkers 用 */
const T0_KIND_LABEL: Record<T0Marker['kind'], string> = {
  'spike-up': '放量急拉',
  'spike-down': '放量急跌',
  'dry-rally': '无量拉升',
  'break-up': '突破',
  'break-down': '破位',
  'trap-bull': '疑似诱多',
  'trap-bear': '疑似诱空'
}

/** 汇总时的固定次序，保证同次数的种类输出顺序稳定（否则地图遍历顺序会变） */
const T0_KIND_ORDER: readonly T0Marker['kind'][] = [
  'spike-up',
  'spike-down',
  'dry-rally',
  'break-up',
  'break-down',
  'trap-bull',
  'trap-bear'
]

/** 命中条数 → 强度。强度只表示「命中几条线索」，不表示「有多确定」 */
function t0Strength(hits: number): 'weak' | 'medium' | 'strong' {
  if (hits >= 3) return 'strong'
  if (hits === 2) return 'medium'
  return 'weak'
}

/**
 * 从分时序列算出全部标记。纯函数，不请求网络。
 *
 * <p>判定顺序即优先级：量能三兄弟（急跌 &gt; 急拉 &gt; 无量拉升）→ 破位 / 突破 → 诱多 / 诱空。
 * 同一分钟只落一个标记，落在哪个取决于这个顺序；突破类同一方向每日只标一次。</p>
 *
 * @param items 分时序列（按时间升序）
 * @param prevClose 昨收，用于在说明里给出相对昨收的涨幅
 * @param options 阈值覆盖
 */
export function computeT0Signals(
  items: readonly { time: string; price: number; volume: number; turnover?: number }[],
  prevClose: number | null,
  options?: T0SignalOptions
): T0Marker[] {
  const list = Array.isArray(items) ? items : []
  if (list.length === 0) return []

  const spikeRatio = t0Number(options?.volumeSpikeRatio, T0_SPIKE_RATIO)
  const surgePct = t0Number(options?.surgePct, T0_SURGE_PCT)
  const dryRatio = t0Number(options?.dryRatio, T0_DRY_RATIO)
  const window = Math.max(2, Math.round(t0Number(options?.volumeWindow, T0_VOLUME_WINDOW)))
  const closeRef = positive(prevClose)

  const prices: number[] = []
  const volumes: number[] = []
  const turnovers: number[] = []
  const minutes: number[] = []
  for (const bar of list) {
    prices.push(positive(bar?.price))
    const vol = Number(bar?.volume)
    volumes.push(Number.isFinite(vol) && vol > 0 ? vol : 0)
    const turn = Number(bar?.turnover)
    turnovers.push(Number.isFinite(turn) && turn > 0 ? turn : 0)
    minutes.push(t0Minute(bar?.time))
  }

  const markers: T0Marker[] = []
  /** 已占用的分钟：同一分钟只留一个标记，避免一根柱子叠两个图标 */
  const usedMinutes = new Set<number>()
  /** 每类信号上一次落盘的分钟，用于同类去重 */
  const lastMinuteOfKind = new Map<T0Marker['kind'], number>()

  /**
   * 落一个标记。返回 false 表示这一分钟已被占用或撞上同类去重窗口。
   *
   * <p>三个拦截条件都在这里统一处理，而不是散落在各判定分支里：
   * ①集合竞价分钟与非法时间整根丢弃；②价格非法丢弃（画在 0 上毫无意义）；
   * ③同一分钟只留一个 + 同类 10 分钟内只留第一个。</p>
   */
  const emit = (
    i: number,
    kind: T0Marker['kind'],
    icon: string,
    detail: string,
    strength?: 'weak' | 'medium' | 'strong'
  ): boolean => {
    const time = list[i]?.time
    if (typeof time !== 'string' || time === '') return false
    const minute = minutes[i]
    if (minute <= T0_AUCTION_MINUTE) return false
    if (prices[i] <= 0) return false
    if (usedMinutes.has(minute)) return false
    const last = lastMinuteOfKind.get(kind)
    if (last !== undefined && minute - last < T0_SAME_KIND_DEDUP_MINUTES) return false
    usedMinutes.add(minute)
    lastMinuteOfKind.set(kind, minute)
    const marker: T0Marker = {
      time,
      price: prices[i],
      kind,
      label: T0_KIND_LABEL[kind],
      icon,
      detail
    }
    if (strength) marker.strength = strength
    markers.push(marker)
    return true
  }

  /** 说明里统一补一句「较昨收 ±x%」，让 tooltip 不只有绝对价 */
  const vsClose = (price: number): string => {
    if (closeRef <= 0 || price <= 0) return ''
    const p = pct(closeRef, price)
    return `，较昨收 ${p >= 0 ? '+' : ''}${p.toFixed(2)}%`
  }

  /** 突破 / 破位是否已经发生过：每只股票每日每方向最多一次 */
  let brokeUp = false
  let brokeDown = false

  for (let i = 0; i < list.length; i++) {
    const minute = minutes[i]
    if (minute <= T0_AUCTION_MINUTE || prices[i] <= 0) continue

    // ── 1. 量能前瞻预警 ──────────────────────────────────────────────
    if (minute >= T0_VOLUME_START_MINUTE && i >= 1 && prices[i - 1] > 0) {
      const change = pct(prices[i - 1], prices[i])
      const base = t0WindowMean(volumes, minutes, i, window)
      if (base > 0) {
        const ratio = volumes[i] / base
        const volText = `量能 ${ratio.toFixed(2)} 倍于近 ${window} 分钟均量`
        if (change <= -surgePct && ratio >= spikeRatio) {
          emit(
            i,
            'spike-down',
            '⚠️',
            `单分钟下跌 ${change.toFixed(2)}%，${volText}${vsClose(prices[i])}`
          )
        } else if (change >= surgePct && ratio >= spikeRatio) {
          emit(
            i,
            'spike-up',
            '⚡',
            `单分钟上涨 ${change.toFixed(2)}%，${volText}${vsClose(prices[i])}`
          )
        } else if (change >= surgePct && ratio < dryRatio) {
          // 涨了却没有量：拉升没有承接，容易回落，所以标「♪」而不是「⚡」
          emit(
            i,
            'dry-rally',
            '♪',
            `单分钟上涨 ${change.toFixed(2)}%，但量能仅 ${ratio.toFixed(2)} 倍均量，上涨缺乏量能支撑${vsClose(prices[i])}`
          )
        }
      }
    }

    // ── 2. 关键位突破 ────────────────────────────────────────────────
    // 关键位用「当日此前最高 / 最低」，而不是开盘价或昨收的整数关口：
    // 整数关口是心理价位、不是真实成交密集区，用它判突破的假信号远多于真信号。
    if (minute >= T0_BREAK_START_MINUTE) {
      const priorHigh = t0Max(prices, 0, i - 1)
      const priorLow = t0Min(prices, 0, i - 1)
      if (!brokeUp && priorHigh > 0 && prices[i] > priorHigh) {
        // 先置位再落盘：即使这一分钟已被量能信号占用，也算「今日已突破过」，
        // 否则下一根创新高会被再次判成「首次突破」
        brokeUp = true
        emit(
          i,
          'break-up',
          '▲',
          `上穿今日此前最高 ${priorHigh.toFixed(2)}${vsClose(prices[i])}`
        )
      }
      if (!brokeDown && priorLow > 0 && prices[i] < priorLow) {
        brokeDown = true
        emit(
          i,
          'break-down',
          '▼',
          `下破今日此前最低 ${priorLow.toFixed(2)}${vsClose(prices[i])}`
        )
      }
    }

    // ── 3. 诱多 / 诱空嫌疑 ───────────────────────────────────────────
    // 这三条全部是启发式推断：主力行为无法从分钟量价直接观测，任何一条都只能说明
    // 「像」，不能说明「是」。所以文案一律写「疑似 / 仅供留意」，强度也只表示命中条数。
    if (minute >= T0_VOLUME_START_MINUTE && i >= 1) {
      const base = t0WindowMean(volumes, minutes, i, window)
      const avgPrice = t0CumulativeAverage(prices, volumes, turnovers, i)
      const priorHigh = t0Max(prices, 0, i - 1)
      const priorLow = t0Min(prices, 0, i - 1)

      // ③ 涨跌量能对比：涨时缩量、跌时放量 = 抬价出货的典型分钟分布
      let upVolume = 0
      let downVolume = 0
      const from3 = Math.max(1, i - window + 1)
      for (let k = from3; k <= i; k++) {
        const v = volumes[k]
        if (v <= 0) continue
        if (prices[k] > prices[k - 1]) upVolume += v
        else if (prices[k] < prices[k - 1]) downVolume += v
      }

      const bullHits: string[] = []
      const bearHits: string[] = []

      // ① 创新高却没有量
      if (base > 0 && priorHigh > 0 && prices[i] > priorHigh && volumes[i] < base * T0_TRAP_VOLUME_RATIO) {
        bullHits.push(`创当日新高但量能仅 ${(volumes[i] / base).toFixed(2)} 倍均量`)
      }
      // ① 创新低却没有量（跌不下去的量价背离，对应的镜像）
      if (base > 0 && priorLow > 0 && prices[i] < priorLow && volumes[i] < base * T0_TRAP_VOLUME_RATIO) {
        bearHits.push(`创当日新低但量能仅 ${(volumes[i] / base).toFixed(2)} 倍均量`)
      }

      // ② 急拉后 10 分钟内回吐超过拉升幅度的一半
      for (let j = Math.max(1, i - T0_TRAP_RETRACE_WINDOW); j <= i; j++) {
        if (prices[j - 1] <= 0) continue
        const move = pct(prices[j - 1], prices[j])
        if (move >= surgePct) {
          const peak = t0Max(prices, j, i)
          if (peak > prices[j - 1] && peak - prices[i] > (peak - prices[j - 1]) * T0_TRAP_RETRACE_RATIO) {
            bullHits.push(`${Math.min(i - j + 1, T0_TRAP_RETRACE_WINDOW)} 分钟内回吐了 ${prices[j - 1].toFixed(2)} → ${peak.toFixed(2)} 这段拉升的一半以上`)
            break
          }
        }
        if (move <= -surgePct) {
          const trough = t0Min(prices, j, i)
          if (trough > 0 && trough < prices[j - 1] && prices[i] - trough > (prices[j - 1] - trough) * T0_TRAP_RETRACE_RATIO) {
            bearHits.push(`${Math.min(i - j + 1, T0_TRAP_RETRACE_WINDOW)} 分钟内收复了 ${prices[j - 1].toFixed(2)} → ${trough.toFixed(2)} 这段下跌的一半以上`)
            break
          }
        }
      }

      // ③ 价格在均价线上方，但成交额贡献集中在下跌分钟
      if (avgPrice > 0 && prices[i] > avgPrice && downVolume > upVolume) {
        bullHits.push(`价格在均价 ${avgPrice.toFixed(2)} 上方，但跌时放量（${Math.round(downVolume)}）大于涨时（${Math.round(upVolume)}）`)
      }
      if (avgPrice > 0 && prices[i] < avgPrice && upVolume > downVolume) {
        bearHits.push(`价格在均价 ${avgPrice.toFixed(2)} 下方，但涨时放量（${Math.round(upVolume)}）大于跌时（${Math.round(downVolume)}）`)
      }

      // 两边都命中时取线索更多的一方；条数相同时按「诱多」优先，因为同样的量价形态
      // 在高位更常见，且提示风险比提示机会更该被看见
      const bullWins = bullHits.length >= bearHits.length
      const hits = bullWins ? bullHits : bearHits
      if (hits.length > 0) {
        const side = bullWins ? '诱多' : '诱空'
        emit(
          i,
          bullWins ? 'trap-bull' : 'trap-bear',
          '🪤',
          `命中 ${hits.length} 条${side}线索：${hits.join('；')}。疑似${side}，仅供留意${vsClose(prices[i])}`,
          t0Strength(hits.length)
        )
      }
    }
  }

  return markers
}

/** 输入兜底：调用方可能传 null / undefined（组件初始值、接口字段缺失），一律当空数组 */
function t0List<T>(value: readonly T[] | null | undefined): readonly T[] {
  return Array.isArray(value) ? value : []
}

/** 按 kind 归类，UI 便于分组展示 */
export function groupMarkers(
  markers: readonly T0Marker[]
): Record<T0Marker['kind'], T0Marker[]> {
  const out: Record<T0Marker['kind'], T0Marker[]> = {
    'spike-up': [],
    'spike-down': [],
    'dry-rally': [],
    'break-up': [],
    'break-down': [],
    'trap-bull': [],
    'trap-bear': []
  }
  for (const marker of t0List(markers)) {
    if (!marker) continue
    const bucket = out[marker.kind]
    // 运行时可能混进未知 kind（比如后端下发脏数据），静默丢弃而不是让 UI 崩掉
    if (bucket) bucket.push(marker)
  }
  return out
}

/** 给标记算一句汇总，如「今日 3 个预警：放量急拉 2、破位 1」；无标记返回空串 */
export function summarizeMarkers(markers: readonly T0Marker[]): string {
  const list = t0List(markers)
  if (list.length === 0) return ''
  const counts = new Map<T0Marker['kind'], number>()
  for (const marker of list) {
    if (!marker) continue
    const kind = marker.kind
    if (!(kind in T0_KIND_LABEL)) continue
    counts.set(kind, (counts.get(kind) ?? 0) + 1)
  }
  if (counts.size === 0) return ''
  const parts = T0_KIND_ORDER.filter((kind) => counts.has(kind))
    // 次数多的排前面，同次数保持 T0_KIND_ORDER 的固定次序（不依赖 Map 遍历顺序）
    .sort((a, b) => (counts.get(b) ?? 0) - (counts.get(a) ?? 0))
    .map((kind) => `${T0_KIND_LABEL[kind]} ${counts.get(kind) ?? 0}`)
  return `今日 ${list.length} 个预警：${parts.join('、')}`
}
