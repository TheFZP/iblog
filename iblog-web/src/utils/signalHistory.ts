/**
 * 历史信号胜率 —— 把「用户实际看过、并且当场真的算出了信号」这件事事后复盘成胜率。
 *
 * <h3>这**不是**策略回测</h3>
 * <p>样本只有一个来源：用户当时恰好打开了某只股票的分时图，前端当场算出信号，于是
 * fire-and-forget 记一行。于是样本天然稀疏，而且带选择偏差 —— 人更可能去看已经涨起来的票，
 * 亏钱的票懒得打开。它只能回答「我最近随手看到的这些标记事后准不准」，不能回答
 * 「这套规则在历史上胜率多少」。规则回测是另一个模块（`utils/signalBacktest.ts`，用日 K
 * 按固定持仓周期重放），两边的数字不能拼在一起讲，也不能把它当策略胜率对外展示；
 * 所以所有对外文案都必须带样本量与 {@link SIGNAL_HISTORY_DISCLAIMER}。</p>
 *
 * <h3>为什么用日 K 收盘价估算</h3>
 * <p>信号价是分时价，但库里只落了日 K，没有历史分钟线；要把每条信号按分钟级重放一遍成本
 * 太高（等于自己维护一套日内行情库）。所以出场价取「信号日之后第 horizon 个交易日的收盘价」。
 * 代价是日内回撤看不见，这个近似会同时高估好信号、低估坏信号 —— 文案里必须写明
 * 「按日 K 收盘估算」，别让读者以为这是真实成交价。</p>
 *
 * <h3>为什么待观察要单独计数</h3>
 * <p>最近几天的信号还没走完持有期。把它们算成失败会系统性压低近期胜率（刚记下的信号必然
 * 全负），算成成功又会虚高；既不进分母也不能直接丢掉，所以单独计 `pendingCount`，
 * 让界面能说清「31 条已结算、11 条待观察」。</p>
 *
 * <h3>为什么持平算输</h3>
 * <p>信号的全部价值在于「后续给出了方向性收益」。收盘价与信号价打平，说明这条标记没有带来
 * 任何可交易的方向，把噪声记成能力只会让胜率虚高。所以只有 `ret > 0` 才算赢。</p>
 */

// ───────────────────────────── 常量 ─────────────────────────────

/** localStorage 键名：信号事件数组。胜率不落库，永远是现算的（口径改了不留旧缓存） */
export const SIGNAL_EVENTS_KEY = 'iblog-signal-events'
/** 事件上限。localStorage 有配额，不设上限迟早写爆；超出按 createdAt 裁最旧的 */
export const MAX_EVENTS = 2000
/** 默认在信号日之后第几个交易日收盘结算 */
export const DEFAULT_HORIZON = 3
/**
 * 可选的持有期（信号日之后第 N 个交易日收盘结算）。
 *
 * <p>为什么要给多档：只看 3 日会把「慢启动」的信号判成错（第 1 天就买、第 4 天才涨），
 * 也会把「快进快出」的信号判成对。1/3/5/10 四档能看出信号是「立刻兑现」还是「要拿一段」，
 * 只看一个数字永远分不清是信号本身烂，还是持有期选错了。</p>
 */
export const HORIZON_CHOICES = [1, 3, 5, 10] as const
/** 默认样本下限：少于这个数只标注、不下结论 */
export const DEFAULT_MIN_SAMPLE = 5
/** 固定免责口径。文案可以拼样本量，但这几句不许弱化 */
export const SIGNAL_HISTORY_DISCLAIMER =
  '样本为用户实际查看时记录的信号，不代表策略胜率；收益按日 K 收盘估算'

// ───────────────────────────── 类型 ─────────────────────────────

/** 信号来源分类，决定后续怎么解读胜率 */
export type SignalSource = 't0' | 'ma-cross' | 'price-alert'

export interface SignalEvent {
  id: string
  code: string
  name?: string
  source: SignalSource
  /** 信号种类短标签，如 '放量急拉'、'突破'、'MA20 上穿'、'价格上破' */
  label: string
  /** 信号方向：看多 / 看空。用于判定「信号兑现」还是「信号失败」 */
  bias: 'bull' | 'bear'
  /** 信号发生时价格，字段名沿用现有项目习惯用 price */
  price: number
  /** 信号日期 yyyy-MM-dd（北京时区） */
  date: string
  /** 可选补充说明，如诱多强度 'medium' */
  note?: string
  /** 记录时刻毫秒时间戳，用于排序与裁剪 */
  createdAt: number
}

export interface WinRateBucket {
  /** 分组键，如 '放量急拉'、'MA20 上穿' */
  label: string
  source: SignalSource
  /** 计入统计的样本数（已有足够后续 K 线） */
  sample: number
  /** 信号兑现的次数（bias='bull' 时后续收盘价高于信号价，bias='bear' 时反之） */
  wins: number
  /** 判负的次数。单独存而不是用 sample-wins 推，是因为持平既不赢也不输 */
  losses: number
  /** 持平次数。不进胜负分母，见模块顶部「为什么持平算输」一节 */
  flats: number
  /** 胜率 0~100，保留一位小数；sample=0 时为 null（不要返回 0，0 会被误读成「全输」） */
  winRate: number | null
  /** 平均后续涨跌幅（百分比，保留两位，按 bias 归一：bull 取正收益、bear 取收益取负后再平均） */
  avgReturn: number | null
  /** 赢的那部分平均盈利（按 bias 归一后的正数）；无盈利样本时为 null */
  avgWin: number | null
  /** 输的那部分平均亏损（按 bias 归一后的正数，便于和 avgWin 直接对比）；无亏损样本时为 null */
  avgLoss: number | null
  /**
   * 盈亏比 = 总盈利 / 总亏损。
   *
   * <p>无亏损时不能返回 Infinity、无盈利时不能返回 0 —— 前者会让界面打印 "Infinity"，
   * 后者会被误读成「不亏不赚」（实际是「全输」）。所以两者都用 null 表示「该比值无意义」。</p>
   */
  profitFactor: number | null
  /** 单条最好成绩（按 bias 归一后的百分比），无样本时 null */
  best: number | null
  /** 单条最差成绩，无样本时 null */
  worst: number | null
}

/** {@link computeWinRates} 的返回值 */
export interface WinRateResult {
  buckets: WinRateBucket[]
  pendingCount: number
  ignoredCount: number
  totalCount: number
  /** 本次统计用的持有期（信号日后第 N 个交易日收盘），便于 UI 显示口径 */
  horizon: number
}

// ───────────────────────────── 存储层 ─────────────────────────────

/**
 * 读 localStorage。
 *
 * <p>必须用 `typeof localStorage === 'undefined'` 而不是直接访问 `localStorage`：本模块会被
 * `node --test` 直接 import，Node 里根本没有这个全局，模块顶层或函数里裸访问会直接
 * ReferenceError。隐私模式 / 配额满则一律 try/catch 吞掉，统计是可选功能，不能把页面搞崩。</p>
 */
function safeGet(key: string): string | null {
  try {
    if (typeof localStorage === 'undefined') return null
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

/** 写 localStorage。返回是否真的落盘（隐私模式 / 配额满时 false，调用方据此判断「是否写入」） */
function safeSet(key: string, value: string): boolean {
  try {
    if (typeof localStorage === 'undefined') return false
    localStorage.setItem(key, value)
    return true
  } catch {
    return false
  }
}

function safeRemove(key: string): void {
  try {
    if (typeof localStorage === 'undefined') return
    localStorage.removeItem(key)
  } catch {
    // 隐私模式 / 配额异常：本地本来也没东西可删，静默即可
  }
}

/** 转有限正数，否则 0（价格、收盘价共用） */
function positive(value: unknown): number {
  const n = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(n) && n > 0 ? n : 0
}

function isSignalSource(value: unknown): value is SignalSource {
  return value === 't0' || value === 'ma-cross' || value === 'price-alert'
}

function genId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

/**
 * 未知值收窄成事件对象，脏数据直接丢弃。
 *
 * <p>localStorage 里的内容可能是任何东西（手工改过、上个版本写的、被别的脚本覆盖过），
 * 一条坏数据不该让整张胜率表算不出来。价格非正的事件也在这里丢掉：它们永远无法结算，
 * 留着只会长期占一个「忽略」计数。</p>
 */
function coerceEvent(raw: unknown): SignalEvent | null {
  if (typeof raw !== 'object' || raw === null) return null
  const item = raw as Record<string, unknown>
  const id = typeof item.id === 'string' ? item.id.trim() : ''
  const code = typeof item.code === 'string' ? item.code.trim() : ''
  const date = typeof item.date === 'string' ? item.date.trim() : ''
  const label = typeof item.label === 'string' ? item.label.trim() : ''
  const price = positive(item.price)
  if (!id || !code || !date || !label || price <= 0) return null
  if (!isSignalSource(item.source)) return null
  if (item.bias !== 'bull' && item.bias !== 'bear') return null
  const createdAt = Number(item.createdAt)
  const name = typeof item.name === 'string' ? item.name.trim() : ''
  const note = typeof item.note === 'string' ? item.note.trim() : ''
  return {
    id,
    code,
    name: name || undefined,
    source: item.source,
    label,
    bias: item.bias,
    price,
    date,
    note: note || undefined,
    createdAt: Number.isFinite(createdAt) ? createdAt : 0
  }
}

/** 读全部事件（未排序、未裁剪）。任何异常都退回空数组 */
function loadEvents(): SignalEvent[] {
  const raw = safeGet(SIGNAL_EVENTS_KEY)
  if (!raw) return []
  try {
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.map(coerceEvent).filter((event): event is SignalEvent => event !== null)
  } catch {
    return []
  }
}

function writeEvents(events: readonly SignalEvent[]): boolean {
  return safeSet(SIGNAL_EVENTS_KEY, JSON.stringify(events))
}

// ───────────────────────────── 记录 ─────────────────────────────

/**
 * 追加一条信号事件并落库。
 *
 * <p>按 `code + date + label` 去重：分时信号是轮询刷新出来的，同一只票同一天可能在几百轮
 * 里反复命中同一个标记，不去重会把 2000 条额度在半小时内吃光，胜率表也会被同一只票刷屏。</p>
 *
 * @returns 是否真的写入（重复、参数非法、localStorage 不可用都返回 false）
 */
export function recordSignalEvent(input: Omit<SignalEvent, 'id' | 'createdAt'>): boolean {
  const code = typeof input?.code === 'string' ? input.code.trim() : ''
  const label = typeof input?.label === 'string' ? input.label.trim() : ''
  const date = typeof input?.date === 'string' ? input.date.trim() : ''
  const price = positive(input?.price)
  const source = input?.source
  const bias = input?.bias
  if (!code || !label || !date || price <= 0) return false
  if (!isSignalSource(source)) return false
  if (bias !== 'bull' && bias !== 'bear') return false

  const events = loadEvents()
  if (events.some((event) => event.code === code && event.date === date && event.label === label)) {
    return false
  }

  const name = typeof input?.name === 'string' ? input.name.trim() : ''
  const note = typeof input?.note === 'string' ? input.note.trim() : ''
  const next: SignalEvent = {
    id: genId(),
    code,
    name: name || undefined,
    source,
    label,
    bias,
    price,
    date,
    note: note || undefined,
    createdAt: Date.now()
  }

  // 先按时间升序再裁：要丢的是「最旧的」，靠 createdAt 而不是数组顺序判断
  const ordered = [...events, next].sort((a, b) => a.createdAt - b.createdAt)
  const kept = ordered.length > MAX_EVENTS ? ordered.slice(ordered.length - MAX_EVENTS) : ordered
  return writeEvents(kept)
}

/** 读取全部事件，按 createdAt 降序（最新记录在最前） */
export function listSignalEvents(): SignalEvent[] {
  return loadEvents().sort((a, b) => b.createdAt - a.createdAt)
}

/** 清空全部记录。不删别的键，也不留胜率缓存 */
export function clearSignalEvents(): void {
  safeRemove(SIGNAL_EVENTS_KEY)
}

/** 某只股票的全部事件，按 createdAt 降序 */
export function signalEventsForCode(code: string): SignalEvent[] {
  const ticker = typeof code === 'string' ? code.trim() : ''
  if (!ticker) return []
  return listSignalEvents().filter((event) => event.code === ticker)
}

// ───────────────────────────── 胜率计算 ─────────────────────────────

/** 后续 K 线的查找结果：三种状态必须分开，混成一个 null 会把「还没走完」当成「数据坏了」 */
type ExitLookup =
  | { kind: 'ok'; close: number }
  | { kind: 'pending' }
  | { kind: 'invalid' }

/** 序列里最小的日 K 形状（只吃日期与收盘价，不依赖 api/stock 的完整类型） */
export interface DailyClose {
  date: string
  close: number
}

/** 取正整数字参数，非法值退回 fallback */
function normalizePositiveInt(value: unknown, fallback: number): number {
  const n = Number(value)
  return Number.isInteger(n) && n > 0 ? n : fallback
}

/**
 * 在升序日 K 里找「date 之后第 horizon 个交易日」的收盘价。
 *
 * <p>日期是 `yyyy-MM-dd`，同格式字符串的字典序就是时间序，直接比较即可。
 * 不要 `new Date()` 解析：浏览器会把 `2026-10-01` 当 UTC 零点，东八区再转回来就成了
 * 前一天，跨时区/夏令时下结果不可复现，而这个判定会被写进胜率。</p>
 */
function lookupExit(
  bars: readonly DailyClose[] | undefined,
  date: string,
  horizon: number
): ExitLookup {
  if (!Array.isArray(bars) || bars.length === 0) return { kind: 'pending' }
  let seen = 0
  for (const bar of bars) {
    if (!bar || typeof bar.date !== 'string') continue
    if (bar.date <= date) continue
    seen += 1
    if (seen === horizon) {
      const close = Number(bar.close)
      // 停牌日 close 可能是 0，接口偶尔给 null/NaN；这些不是「没走完」，是「数据不可用」
      return Number.isFinite(close) && close > 0 ? { kind: 'ok', close } : { kind: 'invalid' }
    }
  }
  return { kind: 'pending' }
}

/** 保留一位小数；顺手把 -0 归一成 0，否则界面会显示 "-0.0%" */
function round1(value: number): number {
  const r = Math.round(value * 10) / 10
  return r === 0 ? 0 : r
}

/** 保留两位小数，同样规避 -0 */
function round2(value: number): number {
  const r = Math.round(value * 100) / 100
  return r === 0 ? 0 : r
}

/** 计算过程中的可变累加器（对外只暴露 WinRateBucket） */
interface BucketAcc {
  label: string
  source: SignalSource
  sample: number
  wins: number
  losses: number
  flats: number
  sum: number
  /** 盈利样本的收益之和（正数） */
  winSum: number
  /** 亏损样本的亏损绝对值之和（正数，所以能直接和 winSum 比出盈亏比） */
  lossSum: number
  best: number
  worst: number
}

/**
 * 计算胜率。
 *
 * <p>口径（改动前先想清楚，这些符号决定了数字对不对）：</p>
 * <ul>
 *   <li>出场价 = 信号日**之后**第 horizon 个交易日的收盘价，`date > event.date` 严格大于，
 *       不能把信号当天的收盘价当出场（那是未来数据，也是自欺欺人）。</li>
 *   <li>后续 K 线不够 → `pendingCount`，不进任何分母。</li>
 *   <li>价格无法用于计算（信号价非正、收盘价非正或非有限数）→ `ignoredCount`。</li>
 *   <li>收益按方向归一：`bias='bear'` 时取负，这样「看空信号跌了」是正收益。</li>
 *   <li>持平（ret === 0）计入样本但不算赢，见模块顶部说明。</li>
 * </ul>
 *
 * <p>`options.minSample` 不参与这里的分组计算（每组的样本数照实给出），它只由
 * {@link sampleWarning} 用来决定「能不能下结论」；放在同一份 options 里是为了调用方能
 * 用一个配置对象同时喂两个函数，不必记住两套默认值。</p>
 *
 * @param events 信号事件
 * @param barsByCode 每只股票的日 K 序列（升序），键是代码
 * @param options.horizon 信号日后第几个交易日收盘算结果，默认 3
 * @param options.minSample 样本少于这个数时该组不建议展示结论，默认 5
 */
export function computeWinRates(
  events: readonly SignalEvent[],
  barsByCode: Readonly<Record<string, readonly { date: string; close: number }[]>>,
  options?: { horizon?: number; minSample?: number }
): WinRateResult {
  const horizon = normalizePositiveInt(options?.horizon, DEFAULT_HORIZON)
  const list: readonly SignalEvent[] = Array.isArray(events) ? events : []
  const buckets = new Map<string, BucketAcc>()
  let pendingCount = 0
  let ignoredCount = 0

  for (const event of list) {
    const price = positive(event?.price)
    const code = typeof event?.code === 'string' ? event.code : ''
    const date = typeof event?.date === 'string' ? event.date : ''
    if (price <= 0 || !code || !date) {
      // 信号价不可用就永远算不出收益，算失败会冤枉这条信号，只能单列出来
      ignoredCount += 1
      continue
    }

    const exit = lookupExit(barsByCode?.[code], date, horizon)
    if (exit.kind === 'pending') {
      pendingCount += 1
      continue
    }
    if (exit.kind === 'invalid') {
      ignoredCount += 1
      continue
    }

    let ret = ((exit.close - price) / price) * 100
    if (event.bias === 'bear') ret = -ret

    const key = `${event.label}\u0000${event.source}`
    const bucket: BucketAcc = buckets.get(key) ?? {
      label: event.label,
      source: event.source,
      sample: 0,
      wins: 0,
      losses: 0,
      flats: 0,
      sum: 0,
      winSum: 0,
      lossSum: 0,
      best: 0,
      worst: 0
    }
    bucket.sample += 1
    bucket.sum += ret
    if (ret > 0) {
      bucket.wins += 1
      bucket.winSum += ret
    } else if (ret < 0) {
      bucket.losses += 1
      bucket.lossSum += -ret
    } else {
      // 持平既不赢也不输：算赢会虚高胜率，算输会压低胜率并污染盈亏比的分母
      bucket.flats += 1
    }
    // 首次进入时 sample 已是 1，best/worst 初始 0 会被这条自身的值覆盖；
    // 用 sample===1 判断首条，避免「首条是负数却仍留下 best=0」的错
    if (bucket.sample === 1) {
      bucket.best = ret
      bucket.worst = ret
    } else {
      if (ret > bucket.best) bucket.best = ret
      if (ret < bucket.worst) bucket.worst = ret
    }
    buckets.set(key, bucket)
  }

  const out: WinRateBucket[] = [...buckets.values()]
    .map((bucket) => {
      // 胜负分母排除持平：`flats` 单列，否则「10 次里 5 次没动」会被算成 50% 胜率
      const decided = bucket.wins + bucket.losses
      return {
        label: bucket.label,
        source: bucket.source,
        sample: bucket.sample,
        wins: bucket.wins,
        losses: bucket.losses,
        flats: bucket.flats,
        winRate: decided > 0 ? round1((bucket.wins / decided) * 100) : null,
        avgReturn: bucket.sample > 0 ? round2(bucket.sum / bucket.sample) : null,
        avgWin: bucket.wins > 0 ? round2(bucket.winSum / bucket.wins) : null,
        avgLoss: bucket.losses > 0 ? round2(bucket.lossSum / bucket.losses) : null,
        // 无亏损时不能返回 Infinity、无盈利时不能返回 0：两者都表示「该比值无意义」
        profitFactor:
          bucket.winSum > 0 && bucket.lossSum > 0 ? round2(bucket.winSum / bucket.lossSum) : null,
        best: bucket.sample > 0 ? round2(bucket.best) : null,
        worst: bucket.sample > 0 ? round2(bucket.worst) : null
      }
    })
    // 样本多的先看：3 条样本的 100% 和 300 条样本的 52% 摆在同一位会误导人
    .sort(
      (a, b) =>
        b.sample - a.sample ||
        a.label.localeCompare(b.label) ||
        a.source.localeCompare(b.source)
    )

  return { buckets: out, pendingCount, ignoredCount, totalCount: list.length, horizon }
}

/**
 * 汇总文案，如「共 42 条记录，31 条已结算、11 条待观察；样本最多的『放量急拉』胜率 58.1%（n=31）」。
 *
 * <p>末尾固定拼上免责口径：这些数字只描述「用户实际看过的那部分样本」，脱离这句话单独展示
 * 就会变成一句假的策略胜率。</p>
 *
 * @returns 无任何记录时返回空串（界面据此整块隐藏，而不是显示「0 条记录」这种噪声）
 */
export function summarizeWinRates(result: ReturnType<typeof computeWinRates>): string {
  const total = Number(result?.totalCount ?? 0)
  if (!Number.isFinite(total) || total <= 0) return ''
  const pending = Number(result?.pendingCount ?? 0) || 0
  const ignored = Number(result?.ignoredCount ?? 0) || 0
  const settled = Math.max(0, total - pending - ignored)
  const buckets = result?.buckets ?? []

  let text = `共 ${total} 条记录，${settled} 条已结算、${pending} 条待观察`
  if (ignored > 0) text += `（另有 ${ignored} 条价格不可用已忽略）`
  const top = buckets[0]
  if (top && top.winRate !== null) {
    text += `；样本最多的『${top.label}』胜率 ${top.winRate.toFixed(1)}%（n=${top.sample}）`
  }
  return `${text}。${SIGNAL_HISTORY_DISCLAIMER}。`
}

/**
 * 数据不足的提醒文案（样本 < minSample 时用）。
 *
 * <p>胜率本身照算（少样本也是事实），但必须标出「别据此下结论」：2 条样本的 100%
 * 和 200 条样本的 52% 是完全不同的可信度，不标注就等于把噪声当结论。</p>
 *
 * @returns 无问题时返回空串
 */
export function sampleWarning(
  result: ReturnType<typeof computeWinRates>,
  minSample: number = DEFAULT_MIN_SAMPLE
): string {
  const min = normalizePositiveInt(minSample, DEFAULT_MIN_SAMPLE)
  const total = Number(result?.totalCount ?? 0) || 0
  if (total <= 0) return ''
  const buckets = result?.buckets ?? []
  if (buckets.length === 0) {
    return `已有 ${total} 条记录，但都还没有足够的后续日 K，暂无法计算胜率。`
  }
  const thin = buckets.filter((bucket) => bucket.sample < min)
  if (thin.length === 0) return ''
  const shown = thin.slice(0, 3)
  const names = shown.map((bucket) => `『${bucket.label}』${bucket.sample} 条`).join('、')
  const more = thin.length > shown.length ? ` 等 ${thin.length} 组` : ''
  return `${names}${more}样本少于 ${min} 条，胜率波动极大，不建议据此下结论。`
}
