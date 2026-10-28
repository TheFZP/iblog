<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  fetchQuote,
  fetchKline,
  fetchIntraday,
  fetchOrderBook,
  fetchMoneyFlow,
  fetchMoneyFlowHistory,
  fetchIndustry,
  type StockQuote,
  type KlineItem,
  type IntradayItem,
  type OrderBook,
  type KlinePeriod,
  type MoneyFlow,
  type MoneyFlowHistoryItem,
  type IndustryData
} from '@/api/stock'
import { useWatchlist } from '@/composables/useWatchlist'
import { usePositions } from '@/composables/usePositions'
import { usePriceAlerts } from '@/composables/usePriceAlerts'
import KlineChart from '@/components/stock/KlineChart.vue'
import IntradayChart from '@/components/stock/IntradayChart.vue'
import DepthPanel from '@/components/stock/DepthPanel.vue'
import MoneyFlowSection from '@/components/stock/MoneyFlowSection.vue'
import IndustryModal from '@/components/stock/IndustryModal.vue'
import PriceAlertDialog from '@/components/stock/PriceAlertDialog.vue'
import PositionDialog from '@/components/stock/PositionDialog.vue'
import {
  KLINE_PERIODS,
  fmtNum,
  fmtSigned,
  fmtVolume,
  fmtTurnover,
  toneClass,
  MINUTE_PERIODS
} from '@/utils/stockFormat'
import { getMarketState, marketStateLabel, refreshIntervalMs } from '@/utils/marketTime'

/**
 * 个股详情页。
 *
 * <p>路由 /stock/:code。桌面版把详情渲染在主面板里（单窗口），网页版用独立路由：
 * 好处是能直接分享链接、浏览器前进后退可用。</p>
 *
 * <h3>竞态处理</h3>
 * <p>切换股票时上一只的请求可能后返回，把旧数据盖到新股票上。用 loadSeq 代际守卫：
 * 每次加载自增，回调里比对序号，不一致直接丢弃。桌面版 PROJECT.md 把这条列为硬性约定。</p>
 *
 * <h3>刷新失败保留旧数据</h3>
 * <p>网络抖动时不能清空已有图表。刷新失败只置 stale 并在页面上标注，
 * 只有「换股票」才清空。</p>
 */

const route = useRoute()
const router = useRouter()
const { isInWatchlist, toggleWatchlist, applyQuotes } = useWatchlist()
const { positionStats, applyPositionQuote } = usePositions()
const { countEnabledForCode } = usePriceAlerts()

const code = computed(() => String(route.params.code ?? ''))

const quote = ref<StockQuote | null>(null)
const quoteStale = ref(false)

const kline = ref<KlineItem[]>([])
const klineLoading = ref(false)
const klineStale = ref(false)
const period = ref<KlinePeriod>('day')

const intraday = ref<IntradayItem[]>([])
const intradayLoading = ref(false)
const intradayStale = ref(false)

const book = ref<OrderBook | null>(null)

const moneyFlow = ref<MoneyFlow | null>(null)
const moneyFlowLoading = ref(false)
const moneyFlowStale = ref(false)
const moneyFlowHistory = ref<MoneyFlowHistoryItem[]>([])
const moneyFlowHistoryLoading = ref(false)

/** 资金流历史只在换股票时拉一次：日线级数据，没必要跟着 15 秒刷新 */
let moneyFlowHistoryLoadedFor = ''

const chartMode = ref<'intraday' | 'kline'>('intraday')
const showAlertDialog = ref(false)
const showPositionDialog = ref(false)

// ---------- 行业分析 ----------
const showIndustryModal = ref(false)
const industryData = ref<IndustryData | null>(null)
const industryLoading = ref(false)
const industryError = ref('')
/** 行业数据是一次性的静态资料，按股票代码缓存，反复开关弹窗不重复请求 */
const industryLoadedFor = ref('')
let industrySeq = 0

const klineRef = ref<InstanceType<typeof KlineChart> | null>(null)
const intradayRef = ref<InstanceType<typeof IntradayChart> | null>(null)

/** 代际守卫：每次切换股票/周期自增，旧响应比对不上就丢弃 */
let quoteSeq = 0
let klineSeq = 0
let intradaySeq = 0
let depthSeq = 0
/** 当日快照与历史是两个独立的请求流，各自的序号互不干扰 —— 共用会导致加载态卡死 */
let moneyFlowSeq = 0
let moneyFlowHistorySeq = 0
let refreshTimer: number | null = null
let themeObserver: MutationObserver | null = null

const position = computed(() => positionStats.value.find((p) => p.code === code.value) ?? null)
const inWatchlist = computed(() => isInWatchlist(code.value))
const alertCount = computed(() => countEnabledForCode(code.value))
const isHk = computed(() => quote.value?.market === 'HK' || /^\d{5}$/.test(code.value))
/** 港股不支持分钟 K 线（后端直接抛错），按钮要禁用而不是让用户点了报错 */
const supportsMinute = computed(() => !isHk.value)
/** 市场状态：结合上游时间戳，能识破「节假日时钟说在交易、实际没数据」 */
const marketState = computed(() => getMarketState(code.value, quote.value?.quoteTime))
const isTrading = computed(() => {
  const s = marketState.value
  return s === 'open' || s === 'break'
})
const marketLabel = computed(() => marketStateLabel(marketState.value))

/** 页面标题跟随股票名 */
watch(
  () => quote.value?.name,
  (name) => {
    if (name) {
      document.title = `${name} ${code.value} - 行情 - iblog`
    }
  }
)

async function loadQuote() {
  const seq = ++quoteSeq
  try {
    const q = await fetchQuote(code.value)
    if (seq !== quoteSeq) {
      return
    }
    quote.value = q
    quoteStale.value = false
    // 同步回自选列表与持仓，避免两处价格不一致
    applyQuotes([q])
    applyPositionQuote(q.code, q)
    // 价格提醒与持仓止损止盈由全局巡查器（useAlertWatcher）统一巡检，
    // 这里不再单独检查 —— 否则详情页开着时会和巡查器重复判定
  } catch {
    if (seq === quoteSeq) {
      // 保留旧数据，只标记 stale
      quoteStale.value = true
    }
  }
}

async function loadKline() {
  const seq = ++klineSeq
  klineLoading.value = true
  try {
    const data = await fetchKline(code.value, period.value)
    if (seq !== klineSeq) {
      return
    }
    kline.value = data
    klineStale.value = false
  } catch {
    if (seq === klineSeq) {
      klineStale.value = true
    }
  } finally {
    if (seq === klineSeq) {
      klineLoading.value = false
    }
  }
}

async function loadIntraday() {
  const seq = ++intradaySeq
  intradayLoading.value = true
  try {
    const data = await fetchIntraday(code.value)
    if (seq !== intradaySeq) {
      return
    }
    intraday.value = data?.items ?? []
    intradayStale.value = false
  } catch {
    if (seq === intradaySeq) {
      intradayStale.value = true
    }
  } finally {
    if (seq === intradaySeq) {
      intradayLoading.value = false
    }
  }
}

async function loadDepth() {
  const seq = ++depthSeq
  try {
    const data = await fetchOrderBook(code.value)
    if (seq !== depthSeq) {
      return
    }
    book.value = data
  } catch {
    // 盘口失败不影响页面其它部分，保留旧数据
  }
}

/**
 * 当日资金流快照。
 *
 * <p>上游在集合竞价/非交易日拿不到当日数据时，后端会退化成「最近交易日 + current=false」，
 * 这里不做特殊处理，交给组件显示日期——否则页面会出现一个空白的「今日」。</p>
 */
async function loadMoneyFlow() {
  const seq = ++moneyFlowSeq
  moneyFlowLoading.value = true
  try {
    const data = await fetchMoneyFlow(code.value)
    if (seq !== moneyFlowSeq) {
      return
    }
    moneyFlow.value = data
    moneyFlowStale.value = false
  } catch {
    if (seq === moneyFlowSeq) {
      // 保留旧快照，只标记旧数据
      moneyFlowStale.value = true
    }
  } finally {
    if (seq === moneyFlowSeq) {
      moneyFlowLoading.value = false
    }
  }
}

/**
 * 近 30 日资金流历史：日线级数据，换股票才重新拉。
 *
 * <p><b>必须用独立的序号</b>：早先和 {@link loadMoneyFlow} 共用一个 `moneyFlowSeq`，
 * 两者并发时后发者的 `++` 会让先发者的序号失效 —— 先发者进不了 finally，
 * `moneyFlowLoading` 永远停在 true，页面就卡在「加载中…」。</p>
 */
async function loadMoneyFlowHistory(force = false) {
  if (!force && moneyFlowHistoryLoadedFor === code.value) {
    return
  }
  const seq = ++moneyFlowHistorySeq
  moneyFlowHistoryLoading.value = true
  try {
    const data = await fetchMoneyFlowHistory(code.value, 30)
    if (seq !== moneyFlowHistorySeq) {
      return
    }
    moneyFlowHistory.value = data ?? []
    moneyFlowHistoryLoadedFor = code.value
  } catch {
    // 历史拿不到就空着，组件会显示「暂无数据」，不影响当日快照
    if (seq === moneyFlowHistorySeq) {
      moneyFlowHistory.value = []
    }
  } finally {
    if (seq === moneyFlowHistorySeq) {
      moneyFlowHistoryLoading.value = false
    }
  }
}

/**
 * 加载行业分析。
 *
 * <p>只在用户点开弹窗时才请求 —— 行业数据是静态资料（报表期级），
 * 每个人打开详情页都去拉一次纯属浪费上游配额。</p>
 */
async function loadIndustry(force = false) {
  const target = code.value
  if (!target) return
  if (!force && industryLoadedFor.value === target && industryData.value) {
    return
  }
  const seq = ++industrySeq
  industryLoading.value = true
  industryError.value = ''
  try {
    const data = await fetchIndustry(target)
    if (seq !== industrySeq) return
    industryData.value = data
    industryLoadedFor.value = target
  } catch (e: unknown) {
    if (seq !== industrySeq) return
    // 失败保留上一次成功的数据（同一只股票重试时），只标错误
    industryError.value = (e as { message?: string })?.message || '网络异常'
  } finally {
    if (seq === industrySeq) {
      industryLoading.value = false
    }
  }
}

function openIndustry() {
  showIndustryModal.value = true
  loadIndustry()
}

/** 跳技术分析页（独立路由：筹码/回测要拉 480 根 K 线，不该塞进详情页首屏） */
function openAnalysis() {
  const target = code.value
  if (!target) return
  void router.push(`/stock/${encodeURIComponent(target)}/analysis`)
}

/** 切换股票：清空旧数据再加载，避免闪一下上一只的图表 */
function resetForNewCode() {
  quote.value = null
  quoteStale.value = false
  kline.value = []
  klineStale.value = false
  intraday.value = []
  intradayStale.value = false
  book.value = null
  moneyFlow.value = null
  moneyFlowStale.value = false
  moneyFlowHistory.value = []
  moneyFlowHistoryLoadedFor = ''
  // 行业数据跟着股票走，换股票必须清掉，否则弹窗里还是上一只的同行
  industryData.value = null
  industryError.value = ''
  industryLoadedFor.value = ''
  // 序号必须一起重置：旧股票的请求若还悬着，回来时不该再把 loading 改回 false
  moneyFlowSeq++
  moneyFlowHistorySeq++
  industrySeq++
  // K 线视图的身份靠「首根日期+数量」区分，换股票必须回到分时并重置周期
  chartMode.value = 'intraday'
  period.value = 'day'
}

async function loadAll() {
  await Promise.all([
    loadQuote(),
    loadKline(),
    loadIntraday(),
    loadDepth(),
    loadMoneyFlow(),
    loadMoneyFlowHistory()
  ])
}

function changePeriod(p: KlinePeriod) {
  if (p === period.value) {
    return
  }
  period.value = p
  loadKline()
}

/** 手动刷新：行情 + 分时 + 盘口 + 当日资金流（K 线与历史资金流是日线数据，不需要刷） */
async function refreshAll() {
  await Promise.all([loadQuote(), loadIntraday(), loadDepth(), loadMoneyFlow()])
}

function startAutoRefresh() {
  stopAutoRefresh()
  scheduleRefresh()
}

/**
 * 动态间隔刷新：每次刷完再排下一次，间隔由市场状态决定。
 *
 * <p>为什么不用 setInterval：固定 15 秒的定时器在收盘后照样跑（用户深夜挂着页面，
 * 一整夜要打上万次上游接口），而 setInterval 里改不了间隔。链式 setTimeout 可以在
 * 每次刷新后按最新状态重新决定下次等多久。</p>
 */
function scheduleRefresh() {
  stopAutoRefresh()
  const state = getMarketState(code.value, quote.value?.quoteTime)
  const delay = refreshIntervalMs(state, document.visibilityState !== 'visible')
  refreshTimer = window.setTimeout(async () => {
    if (document.visibilityState === 'visible') {
      await refreshAll()
    }
    scheduleRefresh()
  }, delay)
}

function stopAutoRefresh() {
  if (refreshTimer !== null) {
    window.clearTimeout(refreshTimer)
    refreshTimer = null
  }
}

/** 深浅色切换：canvas 图表拿不到 CSS 变量，要主动重绘 */
function setupThemeObserver() {
  themeObserver = new MutationObserver(() => {
    klineRef.value?.applyTheme()
    intradayRef.value?.applyTheme()
  })
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
}

/**
 * 从后台切回来时补一次刷新。
 *
 * <p>只在可能真有变化时补：交易时段与午休。盘前/盘后/休市价格不动，
 * 切个标签页就打一遍行情+分时+盘口+资金流四个接口纯属浪费；
 * 这种情况下交给降频定时器，或者用户点「刷新」。</p>
 */
function onVisibilityChange() {
  if (document.visibilityState !== 'visible') {
    return
  }
  const s = getMarketState(code.value, quote.value?.quoteTime)
  if (s === 'open' || s === 'break') {
    refreshAll()
  }
  // 时间可能已经跨过收盘，按最新状态重新排期
  scheduleRefresh()
}

watch(
  code,
  (next, prev) => {
    if (!next) {
      return
    }
    // 同一页面内切换股票（比如从热榜点另一只）要清空，首次进入不清
    if (prev !== undefined) {
      resetForNewCode()
    }
    loadAll()
  },
  { immediate: true }
)

onMounted(() => {
  startAutoRefresh()
  setupThemeObserver()
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onBeforeUnmount(() => {
  stopAutoRefresh()
  themeObserver?.disconnect()
  themeObserver = null
  document.removeEventListener('visibilitychange', onVisibilityChange)
})

function toggleWatch() {
  if (!quote.value) {
    return
  }
  toggleWatchlist({ code: quote.value.code, name: quote.value.name, market: quote.value.market })
}

function goBack() {
  router.push('/stock')
}

const stats = computed(() => {
  const q = quote.value
  if (!q) {
    return []
  }
  return [
    { label: '今开', value: fmtNum(q.open) },
    { label: '昨收', value: fmtNum(q.prevClose) },
    { label: '最高', value: fmtNum(q.high) },
    { label: '最低', value: fmtNum(q.low) },
    { label: '成交量', value: fmtVolume(q.volume) },
    { label: '成交额', value: fmtTurnover(q.turnover) },
    { label: '换手率', value: q.turnoverRate ? `${fmtNum(q.turnoverRate)}%` : '--' },
    { label: '振幅', value: q.amplitude ? `${fmtNum(q.amplitude)}%` : '--' },
    { label: '市盈率', value: q.pe ? fmtNum(q.pe) : '--' }
  ]
})
</script>

<template>
  <div class="detail">
    <button class="detail__back" @click="goBack">← 返回行情</button>

    <!-- 顶部：名称 + 价格 -->
    <header class="hero">
      <div class="hero__id">
        <h1 class="hero__name">{{ quote?.name || code }}</h1>
        <span class="hero__code">{{ code }}</span>
        <span v-if="isHk" class="hero__badge hero__badge--hk">港股</span>
        <span class="hero__badge">{{ marketLabel }}</span>
      </div>

      <div v-if="quote" class="hero__price">
        <span class="hero__now" :class="toneClass(quote.change)">
          {{ quote.currency || (isHk ? 'HK$' : '¥') }}{{ fmtNum(quote.price) }}
        </span>
        <span class="hero__delta" :class="toneClass(quote.change)">
          {{ fmtSigned(quote.change) }}
        </span>
        <span class="hero__delta" :class="toneClass(quote.change)">
          {{ fmtSigned(quote.changePct) }}%
        </span>
        <span v-if="quoteStale" class="hero__stale" title="刷新失败，当前显示的是上次成功获取的数据">
          数据延迟
        </span>
      </div>
      <div v-else class="hero__price hero__price--loading">
        <span class="skeleton" style="width: 160px; height: 30px" />
      </div>

      <div class="hero__actions">
        <button class="btn btn--ghost btn--sm" @click="toggleWatch">
          {{ inWatchlist ? '★ 已自选' : '☆ 加自选' }}
        </button>
        <button class="btn btn--ghost btn--sm" @click="showPositionDialog = true">
          {{ position ? '编辑持仓' : '记录持仓' }}
        </button>
        <button class="btn btn--ghost btn--sm" @click="showAlertDialog = true">
          价格提醒<span v-if="alertCount > 0" class="btn__badge">{{ alertCount }}</span>
        </button>
        <button class="btn btn--ghost btn--sm" @click="openIndustry">行业</button>
        <button class="btn btn--ghost btn--sm" @click="openAnalysis">技术分析</button>
      </div>
    </header>

    <!-- 持仓摘要 -->
    <section v-if="position" class="pos-summary">
      <div class="pos-summary__item">
        <span class="pos-summary__label">成本价</span>
        <span class="pos-summary__value">{{ fmtNum(position.buyPrice) }}</span>
      </div>
      <div class="pos-summary__item">
        <span class="pos-summary__label">持仓数量</span>
        <span class="pos-summary__value">{{ fmtNum(position.quantity, 0) }}</span>
      </div>
      <div class="pos-summary__item">
        <span class="pos-summary__label">市值</span>
        <span class="pos-summary__value">{{ fmtNum(position.marketValue) }}</span>
      </div>
      <div class="pos-summary__item">
        <span class="pos-summary__label">浮动盈亏</span>
        <span class="pos-summary__value" :class="toneClass(position.profit)">
          {{ fmtSigned(position.profit) }}（{{ fmtSigned(position.profitPct) }}%）
        </span>
      </div>
      <div class="pos-summary__item">
        <span class="pos-summary__label">当日盈亏</span>
        <span class="pos-summary__value" :class="toneClass(position.dayProfit)">
          {{ fmtSigned(position.dayProfit) }}
        </span>
      </div>
    </section>

    <!-- 关键指标 -->
    <section class="stats">
      <div v-for="s in stats" :key="s.label" class="stats__item">
        <span class="stats__label">{{ s.label }}</span>
        <span class="stats__value">{{ s.value }}</span>
      </div>
    </section>

    <!-- 图表 -->
    <section class="chart-card">
      <div class="chart-card__head">
        <div class="seg">
          <button class="seg__btn" :class="{ 'seg__btn--on': chartMode === 'intraday' }" @click="chartMode = 'intraday'">
            分时
          </button>
          <button class="seg__btn" :class="{ 'seg__btn--on': chartMode === 'kline' }" @click="chartMode = 'kline'">
            K 线
          </button>
        </div>

        <div v-if="chartMode === 'kline'" class="periods">
          <button
            v-for="p in KLINE_PERIODS"
            :key="p.value"
            class="periods__btn"
            :class="{ 'periods__btn--on': period === p.value }"
            :disabled="p.value !== 'day' && p.value !== 'week' && p.value !== 'month' && !supportsMinute"
            :title="!supportsMinute && MINUTE_PERIODS.has(p.value) ? '港股暂不支持分钟 K 线' : ''"
            @click="changePeriod(p.value)"
          >
            {{ p.label }}
          </button>
        </div>

        <span v-if="(chartMode === 'kline' && klineStale) || (chartMode === 'intraday' && intradayStale)" class="chart-card__stale">
          刷新失败，显示旧数据
        </span>
        <span v-if="!isTrading" class="chart-card__stale">{{ marketLabel }}</span>
      </div>

      <div class="chart-card__body">
        <IntradayChart
          v-if="chartMode === 'intraday'"
          ref="intradayRef"
          :data="intraday"
          :prev-close="quote?.prevClose ?? null"
          :loading="intradayLoading && intraday.length === 0"
        />
        <KlineChart
          v-else
          ref="klineRef"
          :data="kline"
          :period="period"
          :loading="klineLoading && kline.length === 0"
          :cost-price="position?.buyPrice ?? null"
          :prev-close="quote?.prevClose ?? null"
        />
      </div>
    </section>

    <!-- 盘口 -->
    <section class="chart-card chart-card--depth">
      <DepthPanel :book="book" :prev-close="quote?.prevClose ?? null" />
    </section>

    <!-- 主力资金：当日分档 + 近 30 日净流入 -->
    <MoneyFlowSection
      :money-flow="moneyFlow"
      :money-flow-loading="moneyFlowLoading"
      :money-flow-history="moneyFlowHistory"
      :money-flow-history-loading="moneyFlowHistoryLoading"
      :stale="moneyFlowStale"
    />

    <!-- 弹窗 -->
    <PriceAlertDialog
      :show="showAlertDialog"
      :code="code"
      :name="quote?.name || code"
      :price="quote?.price"
      @close="showAlertDialog = false"
    />
    <PositionDialog
      :show="showPositionDialog"
      :editing="position"
      :preset="quote ? { code: quote.code, name: quote.name, price: quote.price } : { code, name: code }"
      @close="showPositionDialog = false"
    />
    <IndustryModal
      :show="showIndustryModal"
      :data="industryData"
      :loading="industryLoading"
      :error="industryError"
      :code="code"
      :change-pct="quote?.changePct"
      @close="showIndustryModal = false"
      @retry="loadIndustry(true)"
    />

    <p class="detail__foot">行情数据来自腾讯财经，延迟数秒。本页不构成任何投资建议。</p>
  </div>
</template>

<style scoped>
.detail {
  max-width: 1180px;
  margin: 0 auto;
  padding: var(--s-6) var(--s-5) var(--s-12);
}

.detail__back {
  margin-bottom: var(--s-4);
  padding: 0;
  border: 0;
  background: none;
  color: var(--c-text-muted);
  font-size: var(--t-sm);
  cursor: pointer;
}

.detail__back:hover {
  color: var(--c-brand);
}

/* ---------- 顶部 ---------- */
.hero {
  display: flex;
  align-items: center;
  gap: var(--s-5);
  flex-wrap: wrap;
  padding-bottom: var(--s-5);
}

.hero__id {
  display: flex;
  align-items: baseline;
  gap: var(--s-2);
  flex-wrap: wrap;
}

.hero__name {
  margin: 0;
  font-size: var(--t-2xl);
  font-weight: 700;
  letter-spacing: -0.015em;
}

.hero__code {
  font-size: var(--t-sm);
  color: var(--c-text-muted);
  font-variant-numeric: tabular-nums;
}

.hero__badge {
  padding: 2px 8px;
  border-radius: var(--r-full);
  background: var(--c-surface-sunken);
  color: var(--c-text-muted);
  font-size: var(--t-xs);
}

.hero__badge--hk {
  background: color-mix(in srgb, #e8b339 18%, transparent);
  color: #a1710d;
}

.hero__price {
  display: flex;
  align-items: baseline;
  gap: var(--s-3);
  font-variant-numeric: tabular-nums;
}

.hero__price--loading {
  min-height: 30px;
}

.hero__now {
  font-size: var(--t-2xl);
  font-weight: 700;
}

.hero__delta {
  font-size: var(--t-base);
  font-weight: 600;
}

.hero__stale {
  padding: 1px 8px;
  border-radius: var(--r-full);
  background: color-mix(in srgb, #e8b339 20%, transparent);
  color: #a1710d;
  font-size: var(--t-xs);
  font-weight: 500;
}

.hero__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2);
  margin-left: auto;
}

.btn__badge {
  margin-left: 4px;
  padding: 0 5px;
  border-radius: var(--r-full);
  background: var(--c-brand);
  color: #fff;
  font-size: 10px;
}

.btn--sm {
  padding: 5px 12px;
  font-size: var(--t-xs);
}

/* ---------- 持仓摘要 ---------- */
.pos-summary {
  display: flex;
  gap: var(--s-6);
  flex-wrap: wrap;
  padding: var(--s-3) var(--s-4);
  margin-bottom: var(--s-5);
  border-radius: var(--r);
  background: var(--c-brand-soft, color-mix(in srgb, var(--c-brand) 8%, transparent));
}

.pos-summary__item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.pos-summary__label {
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.pos-summary__value {
  font-size: var(--t-sm);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

/* ---------- 指标 ---------- */
.stats {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(108px, 1fr));
  gap: var(--s-3);
  padding: var(--s-4);
  margin-bottom: var(--s-5);
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface);
}

.stats__item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.stats__label {
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.stats__value {
  font-size: var(--t-sm);
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}

/* ---------- 图表卡 ---------- */
.chart-card {
  padding: var(--s-4) var(--s-4) var(--s-5);
  margin-bottom: var(--s-5);
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface);
}

/* 盘口卡不铺满：5 档拉太宽会很散，限宽后靠左 */
.chart-card--depth {
  width: fit-content;
  min-width: 340px;
}

.chart-card__head {
  display: flex;
  align-items: center;
  gap: var(--s-4);
  flex-wrap: wrap;
  margin-bottom: var(--s-3);
}

.chart-card__body {
  height: 380px;
}

.chart-card__stale {
  margin-left: auto;
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

/* ---------- 分段控件 ---------- */
.seg {
  display: inline-flex;
  padding: 2px;
  border-radius: var(--r-full);
  background: var(--c-surface-sunken);
}

.seg__btn {
  padding: 4px 14px;
  border: 0;
  border-radius: var(--r-full);
  background: none;
  color: var(--c-text-muted);
  font-size: var(--t-sm);
  cursor: pointer;
  transition: all 0.15s var(--ease-out);
}

.seg__btn--on {
  background: var(--c-surface);
  color: var(--c-text);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

.periods {
  display: flex;
  gap: 2px;
}

.periods__btn {
  padding: 3px 10px;
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  background: none;
  color: var(--c-text-muted);
  font-size: var(--t-xs);
  cursor: pointer;
}

.periods__btn:hover:not(:disabled) {
  background: var(--c-surface-sunken);
}

.periods__btn--on {
  border-color: var(--c-brand);
  color: var(--c-brand);
}

.periods__btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

/* ---------- 页脚 ---------- */
.detail__foot {
  margin: var(--s-6) 0 0;
  font-size: var(--t-xs);
  color: var(--c-text-faint);
  text-align: center;
  line-height: 1.7;
}

/* ---------- 涨跌色（A 股口径：红涨绿跌） ---------- */
.tone-up {
  color: #d93025;
}

.tone-down {
  color: #14803c;
}

.tone-flat {
  color: var(--c-text-secondary);
}

:root[data-theme='dark'] .tone-up {
  color: #ff6b5e;
}

:root[data-theme='dark'] .tone-down {
  color: #45c26f;
}

@media (max-width: 720px) {
  .detail {
    padding: var(--s-4) var(--s-3) var(--s-10);
  }

  .hero__actions {
    margin-left: 0;
    width: 100%;
  }

  .chart-card__body {
    height: 300px;
  }

  .stats {
    grid-template-columns: repeat(auto-fill, minmax(88px, 1fr));
  }
}
</style>
