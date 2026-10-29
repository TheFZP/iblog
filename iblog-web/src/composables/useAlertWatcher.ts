import { watch } from 'vue'
import { fetchQuotes } from '@/api/stock'
import { getMarketState, refreshIntervalMs, isTradingHours, getToday } from '@/utils/marketTime'
import { useNotify } from './useNotify'
import { usePriceAlerts } from './usePriceAlerts'
import { usePositionAlerts } from './usePositionAlerts'
import { useMaAlerts } from './useMaAlerts'
import { useWatchlist } from './useWatchlist'
import { usePositions } from './usePositions'
import { useAuth } from './useAuth'

/**
 * 全局提醒巡查器。
 *
 * <h3>为什么需要它</h3>
 * <p>价格提醒原本是在详情页的 {@code loadQuote()} 里顺带检查的，持仓止损止盈则根本没有巡检。
 * 问题很直接：<b>用户不开着行情页就收不到提醒</b>。而提醒的价值恰恰在于「我不盯着的时候有人替我看」。
 * 所以这里把巡检从页面里摘出来，挂在应用外壳上，按固定节奏轮询所有「被关注的」标的。</p>
 *
 * <h3>关注哪些标的</h3>
 * <p>自选股 ∪ 价格提醒里的标的 ∪ 持仓。三者可能有交集，用 Set 去重，一次批量行情请求搞定。</p>
 *
 * <h3>节奏</h3>
 * <p>复用 {@code marketTime.refreshIntervalMs}：交易时段 15 秒，午休 60 秒，其余 5 分钟；
 * 页面在后台时再 ×4。休市时价格不动，扫得勤只是白白增加上游压力。</p>
 *
 * <h3>已知边界</h3>
 * <p>只在本站点标签页存活期间有效。页面关闭后浏览器不执行 JS，收不到任何提醒 ——
 * 真正的后台提醒需要 Service Worker + 推送服务，不在本次范围内，界面上有相应说明。</p>
 */

/** 单例保护：多个地方调用 start 不应产生多条轮询 */
let started = false
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false

/**
 * 上一轮巡检的时间戳。
 *
 * <p>用途：切回标签页时会补一轮，但用户频繁切标签会在几秒内连打好几轮。
 * 价格提醒靠「上一轮价格 → 本轮价格」判穿越，两轮挨得太近等于拿两个几乎相同的数比较，
 * 首轮刚记下的基准会被立刻覆盖，真正的穿越反而容易漏掉。所以两次巡检之间至少隔这么久。</p>
 */
let lastTickAt = 0
const MIN_TICK_GAP_MS = 5000

/** 用自选股首只（或任一只）代码判断市场，A 股与港股时段不同 */
function probeCode(watchlist: string[], positions: string[], alertCodes: string[]): string | undefined {
  return watchlist[0] ?? positions[0] ?? alertCodes[0]
}

async function tick() {
  lastTickAt = Date.now()

  // 没有通知权限时，判定出来也发不出去（sendNotification 会返回 false，
  // check 函数于是不标记已通知 —— 这是对的，用户放开权限后还该收到）。
  // 既然如此就别扫了：白打一轮上游请求，用户还看不到任何东西。
  // 权限通过行情页的「开启通知」按钮授予，watch 到变化后会重新 start。
  const { permission } = useNotify()
  if (permission.value !== 'granted') {
    return
  }

  const { watchlist } = useWatchlist()
  const { positions } = usePositions()
  const { alerts, checkPriceAlerts } = usePriceAlerts()
  const { checkPositionAlerts } = usePositionAlerts()
  const { maAlerts, checkMaAlerts } = useMaAlerts()

  const codes = new Set<string>()
  for (const s of watchlist.value) {
    if (s.code) codes.add(s.code)
  }
  for (const p of positions.value) {
    if (p.code) codes.add(p.code)
  }
  for (const a of alerts.value) {
    if (a.code && a.enabled) codes.add(a.code)
  }
  // 均线提醒的标的未必在自选/持仓里，漏了就一直不会被巡检
  for (const a of maAlerts.value) {
    if (a.code && a.enabled) codes.add(a.code)
  }

  if (codes.size === 0) {
    return
  }

  let list: Awaited<ReturnType<typeof fetchQuotes>> = []
  try {
    list = await fetchQuotes([...codes])
  } catch {
    // 单次取数失败不打断节奏，下一轮再试
    return
  }
  if (disposed) {
    return
  }

  // A 股「启用提醒」的判定由各自的 check 函数内部负责（它比这里更懂单只标的的时段）
  for (const q of list) {
    if (!(q.price > 0) || q.suspended) {
      continue
    }
    // 价格提醒需要全局开关（页面上的「通知」开关）
    await checkPriceAlerts({ code: q.code, name: q.name, price: q.price }, true)
    await checkPositionAlerts(q.code, q.name, q.price)
    // 均线穿越：用昨收当基准，比「上一轮价格」稳定（不会因为两次巡检挨得近而误判）。
    // 它自己会按交易日期做每日一次去重，所以这里每轮都可以调。
    await checkMaAlerts(q.code, q.prevClose ?? null, q.price, getToday())
  }
}

function schedule() {
  if (disposed) {
    return
  }
  const { watchlist } = useWatchlist()
  const { positions } = usePositions()
  const { alerts } = usePriceAlerts()
  const { maAlerts } = useMaAlerts()

  const code = probeCode(
    watchlist.value.map((s) => s.code),
    positions.value.map((p) => p.code),
    alerts.value
      .filter((a) => a.enabled)
      .map((a) => a.code)
      .concat(maAlerts.value.filter((a) => a.enabled).map((a) => a.code))
  )
  const state = getMarketState(code)
  const ms = refreshIntervalMs(state, document.visibilityState !== 'visible')
  // 先清掉待执行的定时器：onVisible 补扫与常规排期可能同时存在，
  // 不清就会两个定时器并行，越滚越多
  if (timer !== undefined) {
    clearTimeout(timer)
  }
  timer = setTimeout(async () => {
    timer = undefined
    await tick()
    schedule()
  }, ms)
}

/** 页面重新可见时立刻补一轮，不必等下一个周期 */
function onVisible() {
  if (document.visibilityState !== 'visible' || disposed) {
    return
  }
  // 刚扫过就别重复扫：两轮太近会让价格提醒的「上一轮 vs 本轮」失去意义
  if (Date.now() - lastTickAt < MIN_TICK_GAP_MS) {
    return
  }
  // 休市时补检没有意义，反而会在切标签页时制造无谓请求
  const { watchlist } = useWatchlist()
  const code = watchlist.value[0]?.code
  if (!isTradingHours(code)) {
    return
  }
  void tick()
}

/**
 * 启动全局巡查。应在应用外壳（AppLayout）挂载时调用一次。
 */
export function startAlertWatcher() {
  if (started || typeof window === 'undefined') {
    return
  }
  started = true
  disposed = false
  document.addEventListener('visibilitychange', onVisible)
  // 立刻扫一轮再排期。
  // 若只靠 schedule() 排的第一个周期，用户配好提醒后最长的这一段（休市时 5 分钟）是完全没有保护的，
  // 而「刚设完就走」恰恰是最常见的使用方式。
  void tick().finally(() => schedule())
}

/** 停止巡查。应用外壳卸载时调用，主要为了单页内的热更新不留下野定时器 */
export function stopAlertWatcher() {
  started = false
  // 置 true 而不是 false：正在 await 的那一轮 tick() 返回后会走到 schedule()，
  // 只有 disposed 为 true 才能让那个已经派出去的调用不再排下一次。
  disposed = true
  if (timer !== undefined) {
    clearTimeout(timer)
    timer = undefined
  }
  if (typeof document !== 'undefined') {
    document.removeEventListener('visibilitychange', onVisible)
  }
}

/**
 * 为「忘了点开始」的场景兜底：在应用外壳里 watch 一次，
 * 只要用户配置了任一提醒就自动启动巡查，不必额外给用户一个开关。
 *
 * <p>同时 watch 通知权限：没权限时 tick() 会直接返回（判定了也发不出去），
 * 用户点了「开启通知」之后要能立刻真正开始工作，而不是等下一次整页加载。</p>
 */
export function useAutoStartAlertWatcher() {
  const { authenticated } = useAuth()
  const { alerts } = usePriceAlerts()
  const { configs } = usePositionAlerts()
  const { maAlerts } = useMaAlerts()
  const { permission } = useNotify()

  watch(
    () => [
      authenticated.value,
      alerts.value.some((a) => a.enabled),
      Object.keys(configs.value).length > 0,
      maAlerts.value.some((a) => a.enabled),
      permission.value === 'granted'
    ] as const,
    ([isAuthed, hasPriceAlert, hasPositionAlert, hasMaAlert, canNotify]) => {
      // 未登录时行情接口一律 401，巡检只会反复打空请求；权限被收回（或从未授予）时同理，扫了也发不出去
      if (!isAuthed || !canNotify) {
        if (started) {
          stopAlertWatcher()
        }
        return
      }
      if (hasPriceAlert || hasPositionAlert || hasMaAlert) {
        startAlertWatcher()
      }
    },
    { immediate: true }
  )
}
