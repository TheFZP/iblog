import { ref, watch } from 'vue'
import { getToday, isTradingHours, pruneHistory } from '@/utils/marketTime'
import { useNotify } from './useNotify'
import { fetchKline } from '@/api/stock'

/**
 * 价格提醒（localStorage 持久化）。
 *
 * <p>沿用桌面版 {@code stock-analysis-price-alerts} 的 key 与数据结构，便于迁移。</p>
 *
 * <h3>穿越检测</h3>
 * <p>只认「穿越」而不是「大于/小于」：突破 = 上轮价 &lt; 目标价 ≤ 现价，
 * 跌破 = 上轮价 &gt; 目标价 ≥ 现价。首轮只记录基准不判定，跨日自动重置基准 ——
 * 否则昨收 vs 今日开盘的隔夜跳空会被误判成一次突破。</p>
 *
 * <h3>单例</h3>
 * <p>必须单例：行情页的检测器与详情页的配置弹窗共享同一份状态，
 * 否则弹窗里新增的提醒检查器看不见（要重启才生效）。</p>
 */

const STORAGE_KEY = 'stock-analysis-price-alerts'
const HISTORY_KEY = 'stock-analysis-price-alert-history'

export interface PriceAlert {
  id: string
  code: string
  name: string
  /** above=突破（上穿） below=跌破（下穿） */
  direction: 'above' | 'below'
  price: number
  /** 是否附加放量条件 */
  volumeCondition: boolean
  /** 放量倍数：当日量 ≥ N 倍 5 日均量 */
  volumeMultiple: number
  /** once=触发后自动暂停 daily=每个交易日最多一次 */
  mode: 'once' | 'daily'
  enabled: boolean
  createdAt: number
  triggeredAt: number | null
}

function genId(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID()
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

function loadAlerts(): PriceAlert[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const parsed = JSON.parse(raw)
      if (Array.isArray(parsed)) {
        return parsed.filter((a: PriceAlert) => a && a.id && a.code)
      }
    }
  } catch {
    /* 隐私模式 / 脏数据 */
  }
  return []
}

/** 通知历史 { "YYYY-MM-DD": { [alertId]: true } }，只保留 7 天 */
function loadHistory(): Record<string, Record<string, boolean>> {
  try {
    const raw = localStorage.getItem(HISTORY_KEY)
    if (raw) {
      return pruneHistory(JSON.parse(raw)) as Record<string, Record<string, boolean>>
    }
  } catch {
    /* ignore */
  }
  return {}
}

const alerts = ref<PriceAlert[]>(loadAlerts())
const history = ref<Record<string, Record<string, boolean>>>(loadHistory())

/** 价格基准 { [alertId]: 上一轮价格 } */
const baselines = ref<Record<string, number>>({})

/** 上次检测日期：跨日时清空基准，防隔夜跳空误判 */
let lastCheckDate = getToday()

watch(
  alerts,
  (val) => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(val))
    } catch {
      /* ignore quota */
    }
  },
  { deep: true }
)

function saveHistory() {
  history.value = pruneHistory(history.value) as Record<string, Record<string, boolean>>
  try {
    localStorage.setItem(HISTORY_KEY, JSON.stringify(history.value))
  } catch {
    /* ignore */
  }
}

function alertsForCode(code: string): PriceAlert[] {
  return alerts.value.filter((a) => a.code === code).sort((a, b) => b.createdAt - a.createdAt)
}

function countEnabledForCode(code: string): number {
  return alerts.value.filter((a) => a.code === code && a.enabled).length
}

/** 新增提醒；参数非法返回 null */
function addAlert(input: {
  code: string
  name?: string
  direction: 'above' | 'below'
  price: number
  volumeCondition?: boolean
  volumeMultiple?: number
  mode?: 'once' | 'daily'
}): PriceAlert | null {
  const price = Number(input.price)
  if (!input?.code || !price || price <= 0 || Number.isNaN(price)) {
    return null
  }
  const alert: PriceAlert = {
    id: genId(),
    code: String(input.code),
    name: input.name || String(input.code),
    direction: input.direction === 'below' ? 'below' : 'above',
    price,
    volumeCondition: !!input.volumeCondition,
    volumeMultiple: Math.max(1, Number(input.volumeMultiple) || 2),
    mode: input.mode === 'daily' ? 'daily' : 'once',
    enabled: true,
    createdAt: Date.now(),
    triggeredAt: null
  }
  alerts.value = [...alerts.value, alert]
  return alert
}

function updateAlert(id: string, patch: Partial<PriceAlert>) {
  alerts.value = alerts.value.map((a) => (a.id === id ? { ...a, ...patch } : a))
}

function removeAlert(id: string) {
  alerts.value = alerts.value.filter((a) => a.id !== id)
  delete baselines.value[id]
  clearTodayHistory(id)
}

function clearTodayHistory(id: string) {
  const t = getToday()
  if (history.value[t]?.[id]) {
    const nextDay = { ...history.value[t] }
    delete nextDay[id]
    history.value = { ...history.value, [t]: nextDay }
    saveHistory()
  }
}

/** 重新启用（一次性提醒触发后恢复监控） */
function rearmAlert(id: string) {
  updateAlert(id, { enabled: true, triggeredAt: null })
  delete baselines.value[id]
  clearTodayHistory(id)
}

function pauseAlert(id: string) {
  updateAlert(id, { enabled: false })
}

/**
 * 开关提醒的启用状态。
 *
 * <p>开启时等同重新启用：清掉触发时间与基准价，否则刚打开就会被上一条
 * 触发记录拦住，用户会觉得「开了没反应」。</p>
 */
function toggleAlert(id: string, enabled: boolean) {
  if (enabled) {
    rearmAlert(id)
  } else {
    pauseAlert(id)
  }
}

function hasTriggeredToday(id: string): boolean {
  return history.value[getToday()]?.[id] === true
}

function markTriggeredToday(id: string) {
  const t = getToday()
  history.value = pruneHistory({
    ...history.value,
    [t]: { ...(history.value[t] || {}), [id]: true }
  }) as Record<string, Record<string, boolean>>
  saveHistory()
}

/**
 * 当日成交量相对前 5 日均量的倍数。
 *
 * <p>要求日 K 最后一根是今天，否则无法计算（早盘时段累计量天然偏低，
 * 倍数可能达不到阈值 —— 这是指标本身的局限，不是 bug）。</p>
 */
async function volumeRatio(code: string): Promise<number | null> {
  try {
    const kline = await fetchKline(code, 'day')
    if (!kline || kline.length < 7) {
      return null
    }
    const last = kline[kline.length - 1]
    if (last.date !== getToday()) {
      return null
    }
    const vols = kline.map((k) => k.volume)
    const todayVol = vols[vols.length - 1]
    const avg = vols.slice(-6, -1).reduce((a, b) => a + b, 0) / 5
    if (avg <= 0 || todayVol <= 0) {
      return null
    }
    return todayVol / avg
  } catch {
    return null
  }
}

/**
 * 检查单只股票的价格提醒，触发时发通知。应在每次行情刷新后调用。
 *
 * @param quote 行情（至少要有 code / name / price）
 * @param enabled 全局通知开关
 */
async function checkPriceAlerts(
  quote: { code: string; name?: string; price: number },
  enabled: boolean
): Promise<void> {
  if (!quote?.code || !enabled) {
    return
  }
  const list = alerts.value.filter((a) => a.code === quote.code && a.enabled)
  if (list.length === 0) {
    return
  }

  // 跨日重置基准
  const t = getToday()
  if (lastCheckDate !== t) {
    lastCheckDate = t
    baselines.value = {}
  }

  // 非交易时段不检测
  if (!isTradingHours(quote.code)) {
    return
  }

  const { code, name, price } = quote
  const { sendNotification, permission } = useNotify()

  const triggered: { alert: PriceAlert; ratio: number | null }[] = []
  for (const alert of list) {
    const prev = baselines.value[alert.id]
    baselines.value = { ...baselines.value, [alert.id]: price }
    // 首轮只记录基准，不判定
    if (prev == null || prev <= 0) {
      continue
    }

    const crossed =
      alert.direction === 'above'
        ? prev < alert.price && price >= alert.price
        : prev > alert.price && price <= alert.price
    if (!crossed) {
      continue
    }

    let ratio: number | null = null
    if (alert.volumeCondition) {
      ratio = await volumeRatio(code)
      // 无法计算时视为不满足，宁可不报也不误报
      if (ratio == null || ratio < alert.volumeMultiple) {
        continue
      }
    }

    // 每日模式去重；一次性模式触发后即暂停，天然不重复
    if (hasTriggeredToday(alert.id)) {
      continue
    }
    triggered.push({ alert, ratio })
  }

  if (triggered.length === 0) {
    return
  }

  if (permission.value !== 'granted') {
    // 没有通知权限时仍然标记触发与暂停，避免下一轮重复计算；
    // 但用户只能从页面的提醒列表里看到触发状态
    for (const { alert } of triggered) {
      markTriggeredToday(alert.id)
      updateAlert(alert.id, {
        enabled: alert.mode === 'daily',
        triggeredAt: Date.now()
      })
    }
    return
  }

  for (const { alert, ratio } of triggered) {
    const dirLabel = alert.direction === 'above' ? '突破' : '跌破'
    const emoji = alert.direction === 'above' ? '🔔' : '📉'
    let body = `现价 ${price.toFixed(2)} 已${dirLabel}目标价 ${alert.price.toFixed(2)}`
    if (alert.volumeCondition && ratio != null) {
      body += `（放量 ${ratio.toFixed(1)}× 5日均量）`
    }
    await sendNotification(`${emoji} ${dirLabel}提醒: ${name || alert.name} (${code})`, body)

    markTriggeredToday(alert.id)
    updateAlert(alert.id, {
      // once 触发后暂停，等用户手动重新启用
      enabled: alert.mode === 'daily',
      triggeredAt: Date.now()
    })

    // 多条之间间隔 300ms，避免被通知系统节流合并
    if (triggered.length > 1) {
      await new Promise((r) => setTimeout(r, 300))
    }
  }
}

export function usePriceAlerts() {
  return {
    alerts,
    alertsForCode,
    countEnabledForCode,
    addAlert,
    updateAlert,
    removeAlert,
    rearmAlert,
    pauseAlert,
    toggleAlert,
    checkPriceAlerts,
    hasTriggeredToday
  }
}
