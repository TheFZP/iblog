import { computed, ref, watch } from 'vue'
import { useNotify } from './useNotify'
import { usePositions } from './usePositions'
import { isTradingHours } from '@/utils/marketTime'
import {
  judgePositionAlert,
  profitPercent,
  normalizePct,
  type PositionAlertKind
} from '@/utils/positionAlertRules'

/**
 * 持仓止损 / 止盈提醒。
 *
 * <p>逻辑与桌面版 {@code usePositionAlerts.js} 对齐，差异只是把 Tauri 通知换成浏览器通知。
 * 用户为每只持仓设置「亏到多少 % 提示止损」「赚到多少 % 提示止盈」，盘中现价越过阈值时发一条通知。</p>
 *
 * <p>判定规则本身在 {@code @/utils/positionAlertRules}（零依赖，可单测）；
 * 这里只负责存储、时段判断与发通知。</p>
 *
 * <h3>几个刻意的设计</h3>
 * <ul>
 *   <li><b>阈值用百分比而不是价格</b>：用户记的是「亏 8% 就走」，而不是绝对价位；
 *       改了成本价也不用重新设阈值</li>
 *   <li><b>同一标的同一原因每天只报一次</b>：否则股价在阈值附近抖动时会连发几十条，
 *       比不提醒还烦。跨日重置后才允许再报</li>
 *   <li><b>非交易时段不判定</b>：收盘后价格不动，判定只会拿旧价格反复触发</li>
 *   <li><b>通知权限被拒时不标记已通知</b>：用户在浏览器设置里放开权限后应该还能收到，
 *       而不是因为「已经报过」就永远静默</li>
 * </ul>
 */

const STORAGE_KEY = 'iblog-position-alerts'
const HISTORY_KEY = 'iblog-position-notify-history'

/** 每只持仓的提醒配置；数值 <= 0 视为不启用 */
export interface PositionAlertConfig {
  /** 跌幅达到该百分比就提示止损，如 8 表示亏 8% */
  stopLossPct?: number
  /** 涨幅达到该百分比就提示止盈，如 20 表示赚 20% */
  takeProfitPct?: number
}

/** code → 配置 */
type ConfigMap = Record<string, PositionAlertConfig>
/** date(YYYY-MM-DD) → code → 已触发的类型 */
type HistoryMap = Record<string, Record<string, string[]>>

export type { PositionAlertKind }

/** 历史只留最近 7 天，再多就是无用负担 */
const HISTORY_KEEP_DAYS = 7

function today(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function readJson<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key)
    if (!raw) {
      return fallback
    }
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? (parsed as T) : fallback
  } catch {
    // 隐私模式 / 脏数据：当作空配置，不要让整个行情页崩掉
    return fallback
  }
}

function writeJson(key: string, value: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    /* 配额满 / 隐私模式 */
  }
}

const configs = ref<ConfigMap>(readJson<ConfigMap>(STORAGE_KEY, {}))
const history = ref<HistoryMap>(readJson<HistoryMap>(HISTORY_KEY, {}))

watch(configs, (v) => writeJson(STORAGE_KEY, v), { deep: true })
watch(history, (v) => writeJson(HISTORY_KEY, v), { deep: true })

/** 今天已报过的类型，用于去重 */
function triggeredToday(code: string): string[] {
  return history.value[today()]?.[code] ?? []
}

function markTriggered(code: string, kind: PositionAlertKind) {
  const t = today()
  const day = history.value[t] ?? {}
  const list = day[code] ?? []
  if (!list.includes(kind)) {
    day[code] = [...list, kind]
  }
  // 顺手清理过期历史，避免 localStorage 无限增长
  const trimmed: HistoryMap = { [t]: day }
  const keys = Object.keys(history.value)
    .filter((k) => k < t)
    .sort()
    .slice(-(HISTORY_KEEP_DAYS - 1))
  for (const k of keys) {
    trimmed[k] = history.value[k]
  }
  history.value = trimmed
}

/** 读取某只持仓的配置 */
function configFor(code: string): PositionAlertConfig {
  return configs.value[code] ?? {}
}

/** 写入配置；两个阈值都无效时删掉这条记录，别在存储里留空壳 */
function setConfig(code: string, patch: PositionAlertConfig) {
  const next: PositionAlertConfig = {
    stopLossPct: patch.stopLossPct ?? undefined,
    takeProfitPct: patch.takeProfitPct ?? undefined
  }
  const enabled = normalizePct(next.stopLossPct) != null || normalizePct(next.takeProfitPct) != null
  const copy = { ...configs.value }
  if (enabled) {
    copy[code] = next
  } else {
    delete copy[code]
  }
  configs.value = copy
}

/** 清空某只持仓的提醒配置（删除持仓时调用） */
function clearConfig(code: string) {
  if (!(code in configs.value)) {
    return
  }
  const copy = { ...configs.value }
  delete copy[code]
  configs.value = copy
}

/** 启用了任意阈值的持仓数量，用于入口徽标 */
const enabledCount = computed(() => Object.keys(configs.value).length)

/**
 * 检查单只持仓的止损止盈。由全局巡查器在每次行情刷新后调用。
 *
 * @param code  股票代码
 * @param name  股票名（用于通知标题，缺失时用持仓里记的名字）
 * @param price 现价
 */
async function checkPositionAlerts(code: string, name: string | undefined, price: number): Promise<void> {
  const cfg = configFor(code)
  const stopLoss = normalizePct(cfg.stopLossPct)
  const takeProfit = normalizePct(cfg.takeProfitPct)
  if (stopLoss == null && takeProfit == null) {
    return
  }
  if (!(price > 0)) {
    return
  }

  // 成本价来自持仓记录。找不到说明持仓已删除，配置成了孤儿，直接不判定
  const { positions } = usePositions()
  const pos = positions.value.find((p) => p.code === code)
  const buyPrice = pos?.buyPrice ?? 0
  if (!(buyPrice > 0)) {
    return
  }

  // 非交易时段价格不动，判定只会拿收盘价反复触发
  if (!isTradingHours(code)) {
    return
  }

  const hits = judgePositionAlert({
    buyPrice,
    price,
    stopLossPct: stopLoss,
    takeProfitPct: takeProfit,
    done: triggeredToday(code)
  }).map((kind) => ({
    kind,
    pct: kind === 'stop_loss' ? (stopLoss as number) : (takeProfit as number)
  }))
  if (hits.length === 0) {
    return
  }

  const { sendNotification, permission } = useNotify()
  const label = name || pos?.name || code
  // 港股代码是 5 位，其余按 A 股处理（与 PositionStats.currency 的判别口径一致）
  const cur = code.length === 5 ? 'HK$' : '¥'
  const pct = profitPercent(buyPrice, price)

  for (const hit of hits) {
    const isStopLoss = hit.kind === 'stop_loss'
    const emoji = isStopLoss ? '🛑' : '🎯'
    const word = isStopLoss ? '止损' : '止盈'
    const signed = pct > 0 ? `+${pct.toFixed(2)}%` : `${pct.toFixed(2)}%`
    const title = `${emoji} ${word}提醒: ${label} (${code})`
    const body =
      `现价 ${cur}${price.toFixed(2)}，成本 ${cur}${buyPrice.toFixed(2)}，` +
      `当前盈亏 ${signed}，已触及 ${isStopLoss ? '-' : '+'}${hit.pct}% 阈值`

    // 每个标的 + 原因一个独立 tag，避免多条通知互相覆盖
    const sent = await sendNotification(title, body, `iblog-pos-alert-${code}-${hit.kind}`)
    if (!sent) {
      // 权限被拒：不标记已通知，用户放开权限后仍应收到
      return
    }
    markTriggered(code, hit.kind)
    // 多条之间留间隔，避免被通知系统节流合并
    if (hits.length > 1) {
      await new Promise((r) => setTimeout(r, 300))
    }
  }

  // 权限不足时给个显式提示（只在有权限 API 的浏览器里）
  if (permission.value !== 'granted') {
    console.info('[iblog] 持仓提醒已触发但通知权限未授予，请在行情页点击「开启通知」')
  }
}

export function usePositionAlerts() {
  return {
    configs,
    enabledCount,
    configFor,
    setConfig,
    clearConfig,
    checkPositionAlerts
  }
}
