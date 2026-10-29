import { useNotify } from './useNotify.ts'
import { getLimitThreshold } from '../utils/marketLimit.ts'
import { getToday, isTradingHours, pruneHistory } from '../utils/marketTime.ts'

/**
 * 自选列表提醒：盯涨幅/涨跌停/快速变动，命中就弹浏览器通知。
 *
 * <p>与 {@code usePriceAlerts}（按用户设的价格线触发）不同，这里是一组固定的
 * 「异常波动」规则，用户只能开关某一类、不能改阈值：涨停、±7%、±5%，
 * 以及 30 秒一笔的巡检窗口内相对昨收变动超过 N% 的快速拉升/下跌。</p>
 *
 * <p>去重口径：每个「标的 + 触发类型」每天只通知一次，落在 localStorage
 * （{@code iblog-stock-watch-notify-history}），只保留最近 7 天。设置本身不在这里存，
 * 由设置中心持有并每次调用时传进来 —— 这个模块只做判定和发送。</p>
 */

const STORAGE_KEY = 'iblog-stock-watch-notify-history'

/** 触发类型 */
export type WatchTriggerType =
  | 'limit_up'
  | 'limit_down'
  | '+7%'
  | '+5%'
  | '-7%'
  | '-5%'
  | 'fast_rise'
  | 'fast_fall'

/** 触发类型 → 通知标题前缀 */
export const WATCH_TRIGGER_LABELS: Record<WatchTriggerType, string> = {
  limit_up: '🚀 涨停',
  limit_down: '📉 跌停',
  '+7%': '📈 涨超 7%',
  '+5%': '📈 涨超 5%',
  '-7%': '📉 跌超 7%',
  '-5%': '📉 跌超 5%',
  fast_rise: '⚡ 快速拉升',
  fast_fall: '💥 快速下跌'
}

/** 参与判定的行情字段（StockQuote 的子集，便于单测构造） */
export interface WatchNotifyQuote {
  code: string
  name: string
  price: number
  prevClose: number
  changePct: number
}

/** 提醒设置（由设置中心持有，本模块不持久化它） */
export interface WatchNotifySettings {
  notifyEnabled: boolean
  notifyUp5: boolean
  notifyUp7: boolean
  notifyLimitUp: boolean
  notifyDown5: boolean
  notifyDown7: boolean
  notifyLimitDown: boolean
  notifyFastRise: boolean
  notifyFastFall: boolean
  /** 快速变动阈值 %，默认 2，取值 1~5 */
  notifyFastThreshold: number
}

/** 默认设置：全开、快速变动阈值 2%（调用方没传设置时兜底） */
export const DEFAULT_WATCH_NOTIFY_SETTINGS: WatchNotifySettings = {
  notifyEnabled: true,
  notifyUp5: true,
  notifyUp7: true,
  notifyLimitUp: true,
  notifyDown5: true,
  notifyDown7: true,
  notifyLimitDown: true,
  notifyFastRise: true,
  notifyFastFall: true,
  notifyFastThreshold: 2
}

/** { "2026-10-29": { "000001": ["limit_up", "+5%"] } } */
type NotifyHistory = Record<string, Record<string, WatchTriggerType[]>>

/** 通知发送器签名：与 useNotify().sendNotification 一致 */
type NotifySender = (title: string, body: string, tag: string) => Promise<boolean>

// ── 通知发送器的可替换实现（单测用）─────────────────────────────
// 默认走 useNotify().sendNotification。单测里 Node 没有 Notification API，
// 通过 __setNotifySenderForTest 注入一个假的，就不用 mock 整个 useNotify 模块。
let injectedSender: NotifySender | null = null
let defaultSender: NotifySender | null = null

function resolveSender(): NotifySender {
  if (injectedSender) return injectedSender
  if (!defaultSender) {
    // 惰性取一次：useNotify 内部持有全局 permission 状态，重复调用没必要，
    // 但顶层直接调用会在纯 Node 环境（单测导入模块时）多跑一遍副作用
    const { sendNotification } = useNotify()
    defaultSender = (title, body, tag) => sendNotification(title, body, tag)
  }
  return defaultSender
}

/**
 * 仅测试用：替换通知发送器。
 * @param fn 传 null 恢复默认（走 useNotify）
 */
export function __setNotifySenderForTest(fn: NotifySender | null): void {
  injectedSender = fn
  if (fn) defaultSender = null
}

/** 日期 → YYYY-MM-DD（本地时区；不能用 toISOString，东八区 08:00 前会算成前一天） */
function dateKey(at: Date = new Date()): string {
  return `${at.getFullYear()}-${String(at.getMonth() + 1).padStart(2, '0')}-${String(at.getDate()).padStart(2, '0')}`
}

/** 从 localStorage 读历史；隐私模式下读不到就当空 */
function loadHistory(): NotifyHistory {
  if (typeof localStorage === 'undefined') return {}
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return {}
    const parsed: unknown = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object') return {}
    // 读的时候也裁一次：用户可能很久没打开页面，存量的老记录没必要留着
    return pruneHistory(parsed as NotifyHistory, 7) as NotifyHistory
  } catch {
    return {}
  }
}

function saveHistory(next: NotifyHistory): void {
  if (typeof localStorage === 'undefined') return
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
  } catch {
    /* 隐私模式下 setItem 会抛 QuotaExceededError，静默跳过 */
  }
}

// 历史是模块级单例：localStorage 只有一份，多个调用方（自选页 + 设置面板）必须看到同一份数据
let history: NotifyHistory = loadHistory()

/**
 * 自选列表通知系统。
 *
 * @returns 检查/查询/清理接口；`checkAndNotify` 是主要入口，应在每次行情刷新后调用
 */
export function useWatchlistNotifications(): {
  checkAndNotify: (
    quote: WatchNotifyQuote,
    settings?: WatchNotifySettings,
    at?: Date
  ) => Promise<WatchTriggerType[]>
  hasTriggeredToday: (code: string, type: WatchTriggerType) => boolean
  listRecentNotifications: (days?: number) => Array<{
    date: string
    code: string
    types: WatchTriggerType[]
  }>
  clearWatchNotifyHistory: () => void
  resetPriceSnapshots: () => void
  priceSnapshots: Readonly<Record<string, number>>
} {
  // 上一轮价格快照，用于检测「30s 内快速拉升/下跌」。
  // 就地改键而不是整体替换对象：外部拿到的 priceSnapshots 引用要一直有效
  const snapshots: Record<string, number> = {}

  // 上次检查的日期，跨日时清空快照
  let lastCheckDate = getToday()

  function resetPriceSnapshots(): void {
    for (const code of Object.keys(snapshots)) {
      delete snapshots[code]
    }
  }

  // 说明：历史里记的日期固定用 getToday()（真实今天），不跟随 checkAndNotify 的 at 参数。
  // at 只是给单测注入交易时段用的；而落盘前的 pruneHistory 以真实时间为基准，
  // 若把 at 推导出的过去日期写进历史，会被当场裁掉，去重状态等于没写。

  /** 指定标的 + 触发类型今天是否已通知过 */
  function hasTriggeredToday(code: string, type: WatchTriggerType): boolean {
    return history[getToday()]?.[code]?.includes(type) ?? false
  }

  /** 标记为已通知；落盘前把历史裁到最近 7 天 */
  function markTriggeredToday(code: string, type: WatchTriggerType): void {
    const today = getToday()
    const todayData = history[today] ?? {}
    const types = todayData[code] ?? []
    if (types.includes(type)) return
    const next = { ...history, [today]: { ...todayData, [code]: [...types, type] } }
    history = pruneHistory(next, 7) as NotifyHistory
    saveHistory(history)
  }

  /** 清空通知历史（设置面板的「清空记录」） */
  function clearWatchNotifyHistory(): void {
    history = {}
    if (typeof localStorage === 'undefined') return
    try {
      localStorage.removeItem(STORAGE_KEY)
    } catch {
      /* 隐私模式 */
    }
  }

  /** 最近 N 个自然日（含今天）的通知历史，按日期降序（同一天内按代码升序，保证输出稳定） */
  function listRecentNotifications(days = 7): Array<{
    date: string
    code: string
    types: WatchTriggerType[]
  }> {
    // 「最近 N 天」按自然日算：N=1 就只剩今天。若直接用 now - N 天当截至日期，
    // N=1 会把昨天也带进来（设置面板上写「最近 1 天」却显示两条，会被当成 bug）
    const span = Math.max(0, Math.floor(days) - 1)
    const cutoffStr = dateKey(new Date(Date.now() - span * 86400000))
    const out: Array<{ date: string; code: string; types: WatchTriggerType[] }> = []
    for (const [date, byCode] of Object.entries(history)) {
      if (date < cutoffStr) continue
      for (const [code, types] of Object.entries(byCode)) {
        if (types.length > 0) out.push({ date, code, types: [...types] })
      }
    }
    out.sort((a, b) =>
      a.date === b.date ? a.code.localeCompare(b.code) : b.date.localeCompare(a.date)
    )
    return out
  }

  /**
   * 检查单只标的，按阈值发通知。
   *
   * @param quote 行情快照（至少要有 code/name/price/prevClose/changePct）
   * @param settings 提醒设置；不传用 DEFAULT_WATCH_NOTIFY_SETTINGS
   * @param at 判定基准时刻，默认当前（单测注入周末/非交易时段用）
   * @returns 本次实际送达的触发类型
   */
  async function checkAndNotify(
    quote: WatchNotifyQuote,
    settings?: WatchNotifySettings,
    at: Date = new Date()
  ): Promise<WatchTriggerType[]> {
    // ── 1. 数据合法性：脏数据直接放过，别拿 0 元价格去算百分比 ──
    if (!quote || !quote.code) return []
    const { code, name, price, prevClose, changePct } = quote
    if (!Number.isFinite(price) || price <= 0) return []

    // ── 2. 跨日重置价格快照 ──
    // 必须放在交易时段判断之前：隔夜跳空的缺口会被 ((今价 - 昨快照) / 昨收) 算成
    // 一个巨大的「30 秒内快速变动」，次日开盘第一次巡检就会误报快速拉升/下跌。
    // 所以日期一变就先清空快照，让当日第一笔只建立基线、不判定。
    const dayKey = dateKey(at)
    if (lastCheckDate !== dayKey) {
      lastCheckDate = dayKey
      resetPriceSnapshots()
    }

    // ── 3. 非交易时段不判定（按代码区分 A 股/港股时段）──
    if (!isTradingHours(code, at)) {
      snapshots[code] = price
      return []
    }

    const s = settings ?? DEFAULT_WATCH_NOTIFY_SETTINGS
    // ── 4. 总开关关闭：仍然记快照，否则重新打开时第一笔会被当成快速变动 ──
    if (s.notifyEnabled === false) {
      snapshots[code] = price
      return []
    }

    const triggered: WatchTriggerType[] = []

    // ── 5. 静态阈值 ──
    // 涨跌停只对有涨跌幅限制的市场成立（港股 getLimitThreshold 返回 0）
    const limit = getLimitThreshold(code)
    if (limit > 0) {
      if (s.notifyLimitUp && changePct >= limit) triggered.push('limit_up')
      if (s.notifyLimitDown && changePct <= -limit) triggered.push('limit_down')
    }
    // ±5%/±7% 故意不放进 limit > 0 分支：港股虽无涨跌停，但日常 5%/7% 的波动
    // 恰恰是最该盯的。原 prh 实现把这两类也包在 limit > 0 里，导致港股（limit=0）
    // 整块静态阈值被跳过，等于对港股完全不提醒。
    if (s.notifyUp7 && changePct >= 7) {
      triggered.push('+7%')
    } else if (s.notifyUp5 && changePct >= 5) {
      // 与 +7% 互斥：涨 8% 只报「涨超 7%」，不再重复报「涨超 5%」
      triggered.push('+5%')
    }
    if (s.notifyDown7 && changePct <= -7) {
      triggered.push('-7%')
    } else if (s.notifyDown5 && changePct <= -5) {
      triggered.push('-5%')
    }

    // ── 6. 动态阈值：快速拉升 / 快速下跌 ──
    const prev = snapshots[code] ?? 0
    if (prev > 0 && prevClose > 0) {
      const threshold = s.notifyFastThreshold ?? 2
      // 用昨收当分母：两次巡检之间的涨跌换算成「相对昨收变动了几个点」，
      // 这样阈值 2% 的语义与用户理解的涨幅一致（拿 prev 当分母会被低价股放大）
      const rapidPct = ((price - prev) / prevClose) * 100
      if (s.notifyFastRise && rapidPct >= threshold) {
        triggered.push('fast_rise')
      } else if (s.notifyFastFall && rapidPct <= -threshold) {
        triggered.push('fast_fall')
      }
    }

    // ── 7. 无条件更新快照（无论是否触发、是否已经通知过）──
    // 快照是「上一次看到的价」，不是「上一次报警的价」；漏更新会让下一轮
    // 拿两轮之前的价做差，把缓慢爬升累积成一个假的快速拉升
    snapshots[code] = price

    // ── 8. 过滤今天已经通知过的类型 ──
    const pending = triggered.filter((t) => !hasTriggeredToday(code, t))
    if (pending.length === 0) return []

    // ── 9. 逐条发送 ──
    const sent: WatchTriggerType[] = []
    const sign = changePct > 0 ? '+' : ''
    const sender = resolveSender()
    for (let i = 0; i < pending.length; i += 1) {
      const type = pending[i]
      const delivered = await sender(
        `${WATCH_TRIGGER_LABELS[type]}: ${name} (${code})`,
        `当前价 ${price.toFixed(2)}  涨跌幅 ${sign}${changePct.toFixed(2)}%`,
        // tag 必须带上类型和代码：同 tag 的新通知会顶掉旧的，共用 tag 会让
        // 「A 涨停」被「B 跌停」覆盖，用户只看得到最后一条
        `iblog-watch-${type}-${code}`
      )
      // 送达失败（静音或权限被拒）就立即收手：继续循环只会对每条都请求一次权限，
      // 而浏览器在 denied 状态下这笔请求是纯浪费，还会让待发队列堆满无效通知
      if (!delivered) return sent

      // 只有真正弹出去才算「今天报过了」。否则静音期间把状态标成已通知，
      // 用户取消静音后就再也收不到今天的这条提醒了
      markTriggeredToday(code, type)
      sent.push(type)

      // 多条之间留 300ms：浏览器/系统会节流同一站点的连续通知，连发会被合并
      if (i < pending.length - 1) {
        await new Promise((r) => setTimeout(r, 300))
      }
    }
    return sent
  }

  return {
    checkAndNotify,
    hasTriggeredToday,
    listRecentNotifications,
    clearWatchNotifyHistory,
    resetPriceSnapshots,
    priceSnapshots: snapshots
  }
}
