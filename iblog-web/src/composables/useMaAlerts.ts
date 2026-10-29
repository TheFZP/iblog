import { ref, watch } from 'vue'
import { fetchKline, type KlineItem } from '@/api/stock'
import { getToday } from '@/utils/marketTime'
import { sma } from '@/utils/stockIndicators'
import { useNotify } from './useNotify'

/**
 * 均线穿越提醒（localStorage 持久化）。
 *
 * <h3>为什么不是「价格提醒」的复用</h3>
 * <p>价格提醒比的是「上一轮现价 vs 一个写死的目标价」；均线提醒比的是
 * 「昨收 vs 今日收盘对应的 MA(N)」，而 MA 本身每天都在移动。
 * 目标值是算出来的，不能由用户填，所以是独立的一套 store，只是命名与
 * {@link usePriceAlerts} 对称，方便维护者对照着读。</p>
 *
 * <h3>穿越判定</h3>
 * <p>上穿 = {@code 昨收 <= MA && 今收 > MA}；下穿 = {@code 昨收 >= MA && 今收 < MA}。
 * 用「昨收 → 今收」而不是「上一轮轮询 vs 本轮」，是因为收盘价穿越均线是
 * 日线口径的信号：盘中每 15 秒抖一次就发一条通知，比不提醒还烦。</p>
 *
 * <h3>防抖（三道）</h3>
 * <ol>
 *   <li>{@code lastNotifiedDate}：同 code + 同周期 + 同一交易日只通知一次，跨日自动失效
 *       —— 靠比较字符串日期，不需要定时清理</li>
 *   <li>内部收盘价基准：调用方没给昨收时退回「上一轮快照」，首轮只记基准不判定，
 *       跨日清空 —— 否则隔夜跳空会被算成一次穿越</li>
 *   <li>日 K 5 分钟缓存 + 并发去重：巡查器 15 秒一轮，不缓存就等于每轮替所有标的
 *       重拉一遍日 K，纯属白烧上游配额</li>
 * </ol>
 *
 * <h3>单例</h3>
 * <p>与 {@link usePriceAlerts} 一样是模块级单例：配置弹窗与全局巡查器必须共享同一份
 * {@code maAlerts}，否则弹窗里新增的提醒，巡查器要等刷新页面才看得见。</p>
 */

const STORAGE_KEY = 'iblog-stock-ma-alerts'
const HISTORY_KEY = 'iblog-stock-ma-alert-history'

/** 通知历史最多保留条数，超出裁剪最旧的 */
const MAX_HISTORY = 200

/** 日 K 缓存 TTL：5 分钟 */
const BARS_TTL_MS = 5 * 60 * 1000

/** 日 K 缓存条数上限，避免长时间开着页面时 Map 无界增长 */
const BARS_CACHE_LIMIT = 200

/** 可配置的均线周期 */
export const MA_PERIODS = [5, 10, 20, 30, 60] as const

export type MaPeriod = (typeof MA_PERIODS)[number]

/** 配置方向：up=只看上穿 down=只看下穿 both=双向 */
export type MaDirection = 'up' | 'down' | 'both'

/** 单次判定的实际穿越方向 */
export type MaCrossDirection = 'up' | 'down'

export interface MaAlert {
  id: string
  code: string
  name: string
  period: MaPeriod
  direction: MaDirection
  enabled: boolean
  createdAt: number
  /** 最后一次触发的时刻；从未触发为 null */
  triggeredAt: number | null
  /** 最后一次通知的交易日，形如 2026-10-29；用于「每周期每日只通知一次」 */
  lastNotifiedDate: string | null
}

/** 一条通知历史。数组按时间升序，只保留最近 {@link MAX_HISTORY} 条 */
export interface MaAlertHistoryItem {
  id: string
  code: string
  name: string
  period: MaPeriod
  direction: MaCrossDirection
  /** 触发时对应的均线值 */
  ma: number
  prevClose: number
  close: number
  date: string
  at: number
}

/** 一次判定的结果，调用方可据此做日志/UI 提示 */
export interface MaAlertTrigger {
  alert: MaAlert
  period: MaPeriod
  direction: MaCrossDirection
  ma: number
  prevClose: number
  close: number
  date: string
}

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/

/** localStorage 读写一律包一层：隐私模式、配额满、脏数据都不该让提醒功能把页面搞崩 */
function safeGet(key: string): string | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage.getItem(key)
  } catch {
    return null
  }
}

function safeSet(key: string, value: string): void {
  try {
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(key, value)
    }
  } catch {
    /* 隐私模式 / 配额超限：内存态照常工作，只是不落盘 */
  }
}

function genId(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID()
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

const MA_PERIOD_SET: ReadonlySet<number> = new Set(MA_PERIODS)

/** 把外部来源（localStorage 脏数据 / 表单输入）收敛成合法周期 */
function normalizePeriod(value: unknown): MaPeriod | null {
  const n = Number(value)
  if (!Number.isInteger(n) || !MA_PERIOD_SET.has(n)) {
    return null
  }
  return n as MaPeriod
}

function normalizeDirection(value: unknown): MaDirection {
  return value === 'up' || value === 'down' || value === 'both' ? value : 'both'
}

function loadMaAlerts(): MaAlert[] {
  const raw = safeGet(STORAGE_KEY)
  if (!raw) {
    return []
  }
  try {
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) {
      return []
    }
    const out: MaAlert[] = []
    for (const item of parsed) {
      if (!item || typeof item !== 'object') {
        continue
      }
      const r = item as Record<string, unknown>
      const period = normalizePeriod(r.period)
      if (typeof r.id !== 'string' || !r.id || typeof r.code !== 'string' || !r.code || period == null) {
        continue
      }
      out.push({
        id: r.id,
        code: r.code,
        name: typeof r.name === 'string' && r.name ? r.name : r.code,
        period,
        direction: normalizeDirection(r.direction),
        enabled: r.enabled !== false,
        createdAt: typeof r.createdAt === 'number' ? r.createdAt : Date.now(),
        triggeredAt: typeof r.triggeredAt === 'number' ? r.triggeredAt : null,
        lastNotifiedDate:
          typeof r.lastNotifiedDate === 'string' && DATE_RE.test(r.lastNotifiedDate)
            ? r.lastNotifiedDate
            : null
      })
    }
    return out
  } catch {
    return []
  }
}

function loadHistory(): MaAlertHistoryItem[] {
  const raw = safeGet(HISTORY_KEY)
  if (!raw) {
    return []
  }
  try {
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) {
      return []
    }
    const out: MaAlertHistoryItem[] = []
    for (const item of parsed) {
      if (!item || typeof item !== 'object') {
        continue
      }
      const r = item as Record<string, unknown>
      const period = normalizePeriod(r.period)
      const dir = r.direction === 'up' || r.direction === 'down' ? r.direction : null
      if (typeof r.code !== 'string' || !r.code || period == null || !dir) {
        continue
      }
      out.push({
        id: typeof r.id === 'string' && r.id ? r.id : genId(),
        code: r.code,
        name: typeof r.name === 'string' && r.name ? r.name : r.code,
        period,
        direction: dir,
        ma: Number(r.ma) || 0,
        prevClose: Number(r.prevClose) || 0,
        close: Number(r.close) || 0,
        date: typeof r.date === 'string' && DATE_RE.test(r.date) ? r.date : '',
        at: typeof r.at === 'number' ? r.at : Date.now()
      })
    }
    // 老数据可能超过上限，加载时先裁一次
    return out.slice(-MAX_HISTORY)
  } catch {
    return []
  }
}

const maAlerts = ref<MaAlert[]>(loadMaAlerts())
const history = ref<MaAlertHistoryItem[]>(loadHistory())

/**
 * 内部收盘价基准 { [code]: 上一轮收盘价 }。
 *
 * <p>只在调用方没给 {@code prevClose} 时兜底。不建议依赖它：巡查器拿到的
 * {@code quote.prevClose} 是交易所口径，比「上一轮轮询价」准得多。</p>
 */
const baselines = ref<Record<string, number>>({})

/** 基准所属交易日：日期变了就清空 {@link baselines}，防隔夜跳空误判 */
let baselineDate = getToday()

/**
 * 变更即落盘（{@code flush: 'sync'}）。
 *
 * <p>故意不用默认的异步 flush：提醒配置是「关掉页面也不该丢」的数据，
 * 而用户新增一条提醒后可能立刻切走，异步回调不保证跑到。
 * 同步写还有个好处 —— 测试和调用方修改后能立即从 localStorage 读回结果。</p>
 */
watch(
  maAlerts,
  (val) => {
    safeSet(STORAGE_KEY, JSON.stringify(val))
  },
  { deep: true, flush: 'sync' }
)

function saveHistory() {
  history.value = history.value.slice(-MAX_HISTORY)
  safeSet(HISTORY_KEY, JSON.stringify(history.value))
}

// ── 日 K 缓存 ──────────────────────────────────────────────

interface BarsCacheEntry {
  bars: KlineItem[]
  at: number
}

const barsCache = new Map<string, BarsCacheEntry>()
const barsInflight = new Map<string, Promise<KlineItem[]>>()

/**
 * 拉取日 K，带 5 分钟 TTL 缓存与并发去重。
 *
 * <p>巡查器一轮里可能有多只标的、而同一次 tick 又可能因为「切标签页补扫」并发两次，
 * 所以除了 TTL 还要按 code 去重 in-flight 请求，否则同一只票会同时打两个请求。
 * 失败不写缓存 —— 下一轮重试，而不是把一次网络抖动锁死 5 分钟。</p>
 */
async function loadDailyBars(code: string): Promise<KlineItem[]> {
  const hit = barsCache.get(code)
  if (hit && Date.now() - hit.at < BARS_TTL_MS) {
    return hit.bars
  }
  const running = barsInflight.get(code)
  if (running) {
    return running
  }
  const task = (async (): Promise<KlineItem[]> => {
    try {
      const bars = await fetchKline(code, 'day', 120)
      const list = Array.isArray(bars) ? bars.filter((b) => b && Number.isFinite(Number(b.close))) : []
      // 超上限时淘汰最旧插入的一条（Map 保持插入顺序）
      if (barsCache.size >= BARS_CACHE_LIMIT) {
        const oldest = barsCache.keys().next()
        if (!oldest.done) {
          barsCache.delete(oldest.value)
        }
      }
      barsCache.set(code, { bars: list, at: Date.now() })
      return list
    } catch {
      return []
    } finally {
      barsInflight.delete(code)
    }
  })()
  barsInflight.set(code, task)
  return task
}

/**
 * 算「截至本轮收盘」的 MA(period)。
 *
 * <p>上游日 K 在收盘后才会补上当日那根，盘中最后一根往往是昨天。
 * 此时必须把本轮收盘价补到序列末尾，否则算出来的是「截止昨日」的均线，
 * 判定会整体滞后一天 —— 那样提醒永远晚一天，等于没提醒。</p>
 */
function movingAverageAt(
  bars: readonly KlineItem[],
  period: number,
  date: string,
  todayClose: number | null
): number | null {
  const closes = bars.map((b) => Number(b.close))
  const last = bars.length > 0 ? bars[bars.length - 1] : undefined
  if (todayClose != null && (!last || last.date !== date)) {
    closes.push(todayClose)
  }
  if (closes.length < period) {
    return null
  }
  const series = sma(closes, period)
  const ma = series[series.length - 1]
  if (ma == null || !Number.isFinite(ma) || ma <= 0) {
    return null
  }
  return ma
}

// ── 配置 CRUD ──────────────────────────────────────────────

function maAlertsForCode(code: string): MaAlert[] {
  return maAlerts.value.filter((a) => a.code === code).sort((a, b) => b.createdAt - a.createdAt)
}

function countEnabledForCode(code: string): number {
  return maAlerts.value.filter((a) => a.code === code && a.enabled).length
}

/**
 * 新增提醒。
 *
 * <p>同一股票同一周期视为同一条：重复添加时只更新方向并重新启用，
 * 而不是插一条新的 —— 否则同一只票的 MA20 会一天弹两遍通知。</p>
 *
 * @returns 新建或更新后的提醒；参数非法返回 null
 */
function addMaAlert(input: {
  code: string
  name?: string
  period: MaPeriod
  direction?: MaDirection
}): MaAlert | null {
  const code = String(input?.code ?? '').trim()
  const period = normalizePeriod(input?.period)
  if (!code || period == null) {
    return null
  }
  const direction = normalizeDirection(input?.direction)
  const name = input?.name?.trim() || code

  const existing = maAlerts.value.find((a) => a.code === code && a.period === period)
  if (existing) {
    updateMaAlert(existing.id, { direction, name, enabled: true })
    return maAlerts.value.find((a) => a.id === existing.id) ?? null
  }

  const alert: MaAlert = {
    id: genId(),
    code,
    name,
    period,
    direction,
    enabled: true,
    createdAt: Date.now(),
    triggeredAt: null,
    lastNotifiedDate: null
  }
  maAlerts.value = [...maAlerts.value, alert]
  return alert
}

function updateMaAlert(id: string, patch: Partial<MaAlert>) {
  maAlerts.value = maAlerts.value.map((a) => (a.id === id ? { ...a, ...patch, id: a.id } : a))
}

function removeMaAlert(id: string) {
  const target = maAlerts.value.find((a) => a.id === id)
  maAlerts.value = maAlerts.value.filter((a) => a.id !== id)
  if (target && !maAlerts.value.some((a) => a.code === target.code)) {
    // 该股票已经没有提醒了：清掉基准与 K 线缓存，避免长时间运行后无界增长
    const next = { ...baselines.value }
    delete next[target.code]
    baselines.value = next
    barsCache.delete(target.code)
  }
}

/** 重新启用：清掉触发时间与「今日已通知」，否则刚打开就会被上一条记录拦住 */
function rearmMaAlert(id: string) {
  updateMaAlert(id, { enabled: true, triggeredAt: null, lastNotifiedDate: null })
}

function toggleMaAlert(id: string, enabled: boolean) {
  if (enabled) {
    rearmMaAlert(id)
  } else {
    updateMaAlert(id, { enabled: false })
  }
}

/** 指定股票 + 周期的某交易日是否已通知过 */
function hasNotifiedToday(code: string, period: MaPeriod, date: string = getToday()): boolean {
  return maAlerts.value.some((a) => a.code === code && a.period === period && a.lastNotifiedDate === date)
}

function markNotified(alert: MaAlert, trigger: MaAlertTrigger): void {
  updateMaAlert(alert.id, { lastNotifiedDate: trigger.date, triggeredAt: Date.now() })
  history.value = [
    ...history.value,
    {
      id: genId(),
      code: alert.code,
      name: alert.name,
      period: trigger.period,
      direction: trigger.direction,
      ma: trigger.ma,
      prevClose: trigger.prevClose,
      close: trigger.close,
      date: trigger.date,
      at: Date.now()
    }
  ]
  saveHistory()
}

// ── 判定 ──────────────────────────────────────────────────

/**
 * 检查单只股票的均线穿越，命中时发浏览器通知。
 *
 * <p>不做交易时段判断：那是巡查器（{@code useAlertWatcher}）的职责，
 * 放在这里会让「同一次穿越是否成立」取决于调用时刻的墙上时钟，无法单测。
 * 同样不检查全局通知权限 —— 判定与投递解耦，未授权时仍然记录触发状态。</p>
 *
 * @param code 股票代码
 * @param prevClose 昨收；传 null 时退回内部上一轮快照（首轮只记基准不判定）
 * @param close 本轮收盘价（盘中即现价）；null 表示停牌/无成交，直接跳过
 * @param date 交易日 yyyy-MM-dd，用于跨日重置与「每日一次」去重
 * @returns 本轮实际触发（并已写入通知历史）的条目
 */
async function checkMaAlerts(
  code: string,
  prevClose: number | null,
  close: number | null,
  date: string
): Promise<MaAlertTrigger[]> {
  if (!code || !DATE_RE.test(date)) {
    return []
  }
  const list = maAlerts.value.filter((a) => a.code === code && a.enabled)
  if (list.length === 0) {
    return []
  }

  // 跨日：内部基准作废，否则昨日快照会被当成今天的「昨收」
  if (baselineDate !== date) {
    baselineDate = date
    baselines.value = {}
  }

  const today = close != null && Number.isFinite(close) && close > 0 ? Number(close) : null
  if (today == null) {
    return []
  }

  const stored = baselines.value[code]
  // 调用方给的昨收是交易所口径，优先；没给才退回上一轮快照
  const prev = prevClose != null && Number.isFinite(prevClose) && prevClose > 0 ? Number(prevClose) : (stored ?? null)

  baselines.value = { ...baselines.value, [code]: today }

  // 首轮没有基准：只记基准，不判定
  if (prev == null || prev <= 0) {
    return []
  }

  const bars = await loadDailyBars(code)
  if (bars.length === 0) {
    return []
  }

  // 用最新配置再查一遍：await 期间用户可能删掉了某条提醒
  const current = maAlerts.value.filter((a) => a.code === code && a.enabled)
  const triggers: MaAlertTrigger[] = []
  for (const alert of current) {
    const period = normalizePeriod(alert.period)
    if (period == null) {
      continue
    }
    const ma = movingAverageAt(bars, period, date, today)
    if (ma == null) {
      continue
    }
    const crossedUp = prev <= ma && today > ma
    const crossedDown = prev >= ma && today < ma
    if (!crossedUp && !crossedDown) {
      continue
    }
    const direction: MaCrossDirection = crossedUp ? 'up' : 'down'
    if (alert.direction !== 'both' && alert.direction !== direction) {
      continue
    }
    // 同股票同周期同一交易日只通知一次
    if (alert.lastNotifiedDate === date) {
      continue
    }
    triggers.push({ alert, period, direction, ma, prevClose: prev, close: today, date })
  }

  if (triggers.length === 0) {
    return []
  }

  const { sendNotification } = useNotify()
  for (const trigger of triggers) {
    const dirLabel = trigger.direction === 'up' ? '上穿' : '下穿'
    const emoji = trigger.direction === 'up' ? '📈' : '📉'
    const name = trigger.alert.name || trigger.alert.code
    await sendNotification(
      `${emoji} ${dirLabel} MA${trigger.period} 提醒`,
      `${name}(${trigger.alert.code}) 收盘价 ${trigger.close.toFixed(2)} ${dirLabel} MA${trigger.period}(${trigger.ma.toFixed(2)})`,
      // 同一股票同一周期共用一个 tag：重复触发时替换旧通知，而不是堆一屏
      `iblog-ma-${trigger.alert.code}-${trigger.period}`
    )
    // 无论是否送达都标记：这里的口径是「今天这次穿越已经判过」，
    // 不是「今天已经弹过」。不标记的话未授权时每轮巡检都会重复判定同一次穿越。
    markNotified(trigger.alert, trigger)
    // 多条之间间隔 300ms，避免被通知系统节流合并
    if (triggers.length > 1) {
      await new Promise((r) => setTimeout(r, 300))
    }
  }
  return triggers
}

export function useMaAlerts() {
  return {
    maAlerts,
    history,
    maAlertsForCode,
    countEnabledForCode,
    addMaAlert,
    updateMaAlert,
    removeMaAlert,
    toggleMaAlert,
    rearmMaAlert,
    hasNotifiedToday,
    checkMaAlerts
  }
}
