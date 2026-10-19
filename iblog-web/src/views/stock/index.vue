<template>
  <div class="stock-page">
    <div class="layout">
      <!-- 页头：标题 + 刷新状态 -->
      <header class="page-head">
        <div class="page-head__main">
          <h1 class="page-title">行情</h1>
          <p class="page-desc">
            自选股实时行情与市场热榜。数据来自腾讯财经与同花顺，{{ stateBadge.live ? `每 ${refreshSec} 秒自动刷新` : '休市期间降频刷新' }}。
          </p>
        </div>
        <div class="page-head__side">
          <span class="refresh-state" :class="{ 'is-live': stateBadge.live }">
            <span class="dot" aria-hidden="true" />
            {{ stateBadge.text }}<template v-if="lastQuoteTime"> · {{ lastQuoteTime }}</template>
          </span>
          <button
            class="alert-btn"
            :class="{ 'is-on': notifyGranted && alertCount > 0 }"
            type="button"
            :disabled="notifyUnsupported || notifyRequesting || notifyGranted"
            :title="
              notifyGranted
                ? '通知已开启，价格与止损止盈提醒会在后台巡检'
                : '授权浏览器通知，行情越过你设的价位时弹出提醒'
            "
            @click="enableNotify"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M18 8a6 6 0 1 0-12 0c0 7-3 8-3 8h18s-3-1-3-8" />
              <path d="M13.7 21a2 2 0 0 1-3.4 0" />
            </svg>
            <span>{{ notifyRequesting ? '授权中' : alertHint }}</span>
          </button>
          <button
            class="btn btn--ghost refresh-btn"
            type="button"
            :disabled="refreshing"
            @click="refreshAll()"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true" :class="{ spin: refreshing }">
              <path d="M20 11a8 8 0 1 0-.6 4M20 5v6h-6" />
            </svg>
            {{ refreshing ? '刷新中' : '刷新' }}
          </button>
        </div>
      </header>

      <!-- 大盘指数 -->
      <section class="indices" aria-label="大盘指数">
        <template v-if="indices.length">
          <div v-for="idx in indices" :key="idx.code" class="index-card">
            <span class="index-name">{{ idx.name }}</span>
            <span class="index-price" :class="toneClass(idx.change)">{{ fmt(idx.price) }}</span>
            <span class="index-change" :class="toneClass(idx.change)">
              {{ signed(idx.change) }} ({{ pct(idx.changePct) }})
            </span>
          </div>
        </template>
        <template v-else>
          <div v-for="n in 6" :key="n" class="index-card">
            <span class="skeleton" style="width: 56px; height: 12px" />
            <span class="skeleton" style="width: 78px; height: 18px; margin-top: 8px" />
            <span class="skeleton" style="width: 66px; height: 12px; margin-top: 8px" />
          </div>
        </template>
      </section>

      <div class="page-grid stock-grid">
        <!-- 主栏 -->
        <div class="stock-main">
          <!-- 搜索 -->
          <form class="stock-search" role="search" @submit.prevent>
            <svg class="stock-search__icon" viewBox="0 0 24 24" aria-hidden="true">
              <circle cx="11" cy="11" r="7" />
              <path d="m20 20-3.6-3.6" />
            </svg>
            <input
              v-model="keyword"
              type="search"
              placeholder="搜索股票代码或名称，如 600519 / 茅台"
              aria-label="搜索股票"
              @input="onSearchInput"
              @focus="showDropdown = true"
              @keydown.esc="closeDropdown"
            />
            <span v-if="searching" class="stock-search__hint">搜索中…</span>

            <!-- 搜索结果下拉 -->
            <transition name="drop">
              <ul v-if="showDropdown && results.length" class="stock-dropdown" role="listbox">
                <li v-for="r in results" :key="r.code">
                  <button type="button" role="option" :aria-selected="false" @click="pick(r)">
                    <span class="stock-dropdown__name">{{ r.name }}</span>
                    <span class="stock-dropdown__code">{{ r.displayCode }}</span>
                    <span
                      class="stock-dropdown__act"
                      :class="{ 'is-on': isInWatchlist(r.code) }"
                      @click.stop="toggleStock(r)"
                    >
                      {{ isInWatchlist(r.code) ? '已自选' : '+ 自选' }}
                    </span>
                  </button>
                </li>
              </ul>
            </transition>
          </form>

          <!-- 自选股列表 -->
          <section class="panel" aria-label="自选股">
            <div class="panel__head">
              <h2 class="panel__title">
                自选股
                <span class="panel__count">{{ watchlist.length }}</span>
              </h2>
              <button
                v-if="watchlist.length"
                class="link-btn"
                type="button"
                @click="confirmClear = true"
              >
                清空
              </button>
            </div>

            <div v-if="!watchlist.length" class="empty-state">
              <svg class="empty-state__icon" viewBox="0 0 24 24" aria-hidden="true">
                <path d="M12 4v16M4 12h16" />
              </svg>
              还没有自选股，在上方搜索添加
            </div>

            <ul v-else class="watch-list">
              <li
                v-for="s in watchlist"
                :key="s.code"
                class="watch-row"
                :class="{ 'is-suspended': isSuspended(s.code) }"
              >
                <button
                  class="watch-row__main"
                  type="button"
                  :aria-label="`查看 ${s.name} 详情`"
                  @click="openDetail(s.code)"
                >
                  <div class="watch-row__id">
                    <span class="watch-row__name">{{ s.name }}</span>
                    <span class="watch-row__code">{{ s.code }}</span>
                  </div>
                  <div class="watch-row__price">
                    <span :class="toneClass(quoteOf(s.code)?.change)">{{ fmt(s.price) }}</span>
                    <span v-if="s.suspended" class="chip chip--brand">停牌</span>
                  </div>
                  <div class="watch-row__chg" :class="toneClass(quoteOf(s.code)?.change)">
                    <span>{{ signed(s.change) }}</span>
                    <span>{{ pct(s.changePct) }}</span>
                  </div>
                </button>
                <button
                  class="watch-row__del"
                  type="button"
                  :aria-label="`从自选移除 ${s.name}`"
                  @click="removeFromWatchlist(s.code)"
                >
                  <svg viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M6 6l12 12M18 6L6 18" />
                  </svg>
                </button>
              </li>
            </ul>
          </section>
        </div>

        <!-- 侧栏：热榜 -->
        <aside class="stock-side">
          <section class="panel" aria-label="热榜">
            <div class="panel__head">
              <h2 class="panel__title">人气热榜</h2>
              <span class="panel__meta">同花顺</span>
            </div>

            <p v-if="hotError" class="panel__error">{{ hotError }}</p>

            <ol v-else-if="hotList.length" class="hot-list">
              <li v-for="h in hotList" :key="h.code" class="hot-row">
                <span class="hot-row__rank" :class="{ 'is-top': h.order <= 3 }">{{ h.order }}</span>
                <button
                  class="hot-row__main hot-row__main--btn"
                  type="button"
                  :aria-label="`查看 ${h.name} 详情`"
                  @click="openDetail(h.code)"
                >
                  <div class="hot-row__top">
                    <span class="hot-row__name">{{ h.name }}</span>
                    <span class="hot-row__chg" :class="toneClass(h.riseAndFall)">
                      {{ pct(h.riseAndFall) }}
                    </span>
                  </div>
                  <div class="hot-row__sub">
                    <span class="hot-row__code">{{ h.code }}</span>
                    <span v-if="h.popularityTag" class="chip">{{ h.popularityTag }}</span>
                  </div>
                </button>
                <button
                  class="hot-row__add"
                  type="button"
                  :aria-label="`添加 ${h.name} 到自选`"
                  :disabled="isInWatchlist(h.code)"
                  @click="addHot(h)"
                >
                  {{ isInWatchlist(h.code) ? '✓' : '+' }}
                </button>
              </li>
            </ol>

            <div v-else class="hot-skeleton">
              <div v-for="n in 8" :key="n" class="hot-skeleton__row">
                <span class="skeleton" style="width: 14px; height: 14px; border-radius: 50%" />
                <span class="skeleton" style="flex: 1; height: 14px" />
                <span class="skeleton" style="width: 46px; height: 14px" />
              </div>
            </div>
          </section>
        </aside>
      </div>
    </div>

    <!-- 清空确认 -->
    <transition name="fade">
      <div v-if="confirmClear" class="modal" @click.self="confirmClear = false">
        <div class="modal__box" role="dialog" aria-modal="true" aria-labelledby="clear-title">
          <h3 id="clear-title" class="modal__title">清空自选股？</h3>
          <p class="modal__desc">这会移除全部 {{ watchlist.length }} 只股票，操作不可撤销。</p>
          <div class="modal__actions">
            <button class="btn btn--ghost" type="button" @click="confirmClear = false">取消</button>
            <button class="btn" type="button" @click="clearAll">确认清空</button>
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  fetchHotList,
  fetchIndices,
  fetchQuotes,
  searchStock,
  type HotStockItem,
  type MarketIndex,
  type SearchResult
} from '@/api/stock'
import { useWatchlist, type WatchStock } from '@/composables/useWatchlist'
import { useNotify } from '@/composables/useNotify'
import { usePositionAlerts } from '@/composables/usePositionAlerts'
import { usePriceAlerts } from '@/composables/usePriceAlerts'
import { getMarketState, marketStateBadge, refreshIntervalMs } from '@/utils/marketTime'

const router = useRouter()

/** 跳转个股详情页 */
function openDetail(code: string) {
  if (code) {
    router.push(`/stock/${code}`)
  }
}

const {
  watchlist,
  isInWatchlist,
  addToWatchlist,
  removeFromWatchlist,
  toggleWatchlist,
  applyQuotes
} = useWatchlist()

const indices = ref<MarketIndex[]>([])
const hotList = ref<HotStockItem[]>([])
const hotError = ref('')
const refreshing = ref(false)

const keyword = ref('')
const results = ref<SearchResult[]>([])
const searching = ref(false)
const showDropdown = ref(false)
const confirmClear = ref(false)

// ---------- 提醒状态 ----------
//
// 提醒的「触发」由 AppLayout 里的全局巡查器负责（关掉本页也照常巡检），
// 这里只展示状态并提供开启浏览器通知的入口 —— 两者是同一套数据，不会各说各话。

const { permission: notifyPermission, requestNotifyPermission } = useNotify()
const { alerts: priceAlerts } = usePriceAlerts()
const { enabledCount: positionAlertCount } = usePositionAlerts()

/** 当前启用的提醒条数：价格提醒按目标数算，持仓按设置了阈值的股票数算 */
const alertCount = computed(
  () => priceAlerts.value.filter((a) => a.enabled).length + positionAlertCount.value
)

/** 浏览器通知是否已授权 */
const notifyGranted = computed(() => notifyPermission.value === 'granted')

/** 浏览器不支持 Notification（如部分内网浏览器） */
const notifyUnsupported = computed(() => notifyPermission.value === 'unsupported')

/**
 * 提醒入口的展示文案。
 *
 * 三种情况分开说：不支持 / 未授权 / 已授权但一条没配。
 * 统称「提醒」会让人以为已经开着，于是不被通知时以为坏了。
 */
const alertHint = computed(() => {
  if (notifyUnsupported.value) return '浏览器不支持通知'
  if (!notifyGranted.value) return '开启通知'
  if (alertCount.value === 0) return '提醒'
  return `${alertCount.value} 条提醒`
})

const notifyRequesting = ref(false)

async function enableNotify() {
  if (notifyRequesting.value || notifyGranted.value) return
  notifyRequesting.value = true
  try {
    await requestNotifyPermission()
  } finally {
    notifyRequesting.value = false
  }
}

/** 当前市场状态：用大盘指数自带的上游时间戳做反证，比纯时钟判断可靠 */
const marketState = computed(() => getMarketState(undefined, indices.value[0]?.quoteTime))

/** 顶部状态胶囊：交易中 / 午间休市 / 未开盘 / 已收盘 / 休市 */
const stateBadge = computed(() => marketStateBadge(marketState.value))

/** 是否在交易时段（保留旧名，模板里已在用） */
const isTradingTime = computed(() => {
  const s = marketState.value
  return s === 'open' || s === 'break'
})

/** 当前刷新间隔（秒），展示给用户看，避免「为什么一直不刷新」的困惑 */
const refreshSec = computed(() => refreshIntervalMs(marketState.value) / 1000)

/** 最新一条上游时间戳，格式化成 HH:mm:ss */
const lastQuoteTime = computed(() => {
  const t = indices.value.find((i) => i.quoteTime)?.quoteTime
  const m = t ? /(\d{2}:\d{2}:\d{2})/.exec(t) : null
  return m ? m[1] : ''
})

/** 停牌判断：报价里带上的，用统一入口避免模板里到处取 quote */
function isSuspended(code: string) {
  return quotes.value.get(code)?.suspended === true
}

const quotes = ref(new Map<string, { change?: number; suspended?: boolean }>())

function quoteOf(code: string) {
  return quotes.value.get(code)
}

// ---------- 格式化 ----------

function fmt(n: number | undefined) {
  if (n == null || Number.isNaN(n)) return '--'
  return n.toFixed(2)
}

function signed(n: number | undefined) {
  if (n == null || Number.isNaN(n)) return '--'
  return `${n > 0 ? '+' : ''}${n.toFixed(2)}`
}

function pct(n: number | undefined) {
  if (n == null || Number.isNaN(n)) return '--'
  return `${n > 0 ? '+' : ''}${n.toFixed(2)}%`
}

/** A 股红涨绿跌 */
function toneClass(n: number | undefined) {
  if (n == null || Number.isNaN(n) || n === 0) return 'tone-flat'
  return n > 0 ? 'tone-up' : 'tone-down'
}

// ---------- 数据加载 ----------

async function loadIndices() {
  try {
    indices.value = await fetchIndices()
  } catch {
    // 指数失败时保留上一次数据，不清空，避免整块闪没
  }
}

async function loadHot() {
  try {
    hotList.value = await fetchHotList()
    hotError.value = ''
  } catch (e: any) {
    hotError.value = e?.message || '热榜获取失败'
  }
}

async function loadQuotes() {
  const codes = watchlist.value.map((s) => s.code)
  if (!codes.length) {
    quotes.value = new Map()
    return
  }
  try {
    const list = await fetchQuotes(codes)
    const toneMap = new Map(list.map((q) => [q.code, { change: q.change, suspended: q.suspended }]))
    quotes.value = toneMap
    applyQuotes(list)
  } catch {
    // 单次失败不打断自动刷新节奏
  }
}

/** 并行刷新全部数据；手动点击时给出 busy 态 */
async function refreshAll(showBusy = true) {
  if (showBusy) refreshing.value = true
  try {
    await Promise.all([loadIndices(), loadQuotes(), loadHot()])
  } finally {
    if (showBusy) refreshing.value = false
  }
}

// ---------- 搜索 ----------

let searchTimer: ReturnType<typeof setTimeout> | undefined
/** 防止旧请求后返回覆盖新结果 */
let searchSeq = 0

function onSearchInput() {
  showDropdown.value = true
  clearTimeout(searchTimer)
  const k = keyword.value.trim()
  if (!k) {
    results.value = []
    searching.value = false
    return
  }
  searching.value = true
  searchTimer = setTimeout(async () => {
    const seq = ++searchSeq
    try {
      const r = await searchStock(k)
      if (seq === searchSeq) {
        results.value = r
      }
    } catch {
      if (seq === searchSeq) {
        results.value = []
      }
    } finally {
      if (seq === searchSeq) {
        searching.value = false
      }
    }
  }, 260)
}

function closeDropdown() {
  showDropdown.value = false
  keyword.value = ''
  results.value = []
}

function pick(r: SearchResult) {
  const stock: WatchStock = { code: r.code, name: r.name, market: r.market }
  if (!isInWatchlist(r.code)) {
    addToWatchlist(stock)
  }
  closeDropdown()
  loadQuotes()
}

function toggleStock(r: SearchResult) {
  toggleWatchlist({ code: r.code, name: r.name, market: r.market })
  loadQuotes()
}

function addHot(h: HotStockItem) {
  addToWatchlist({ code: h.code, name: h.name })
  loadQuotes()
}

function clearAll() {
  watchlist.value = []
  confirmClear.value = false
  quotes.value = new Map()
}

// ---------- 生命周期 ----------

let timer: ReturnType<typeof setTimeout> | undefined

/**
 * 链式定时刷新：每次刷完按最新市场状态决定下次等多久。
 *
 * <p>固定 15 秒的问题：收盘后、深夜、周末照样全量刷四类接口，白烧用户流量与后端上游配额；
 * 而 setInterval 改不了间隔。交易中 15 秒 / 午休 60 秒 / 其余 5 分钟，页面在后台再 ×4。</p>
 */
function scheduleRefresh() {
  clearTimeout(timer)
  const delay = refreshIntervalMs(marketState.value, document.visibilityState !== 'visible')
  timer = setTimeout(async () => {
    if (document.visibilityState === 'visible') {
      await refreshAll(false)
    }
    scheduleRefresh()
  }, delay)
}

onMounted(() => {
  refreshAll(false)
  scheduleRefresh()
  // 切回前台时立刻补一次，别让用户盯着过期数据等到下一个 5 分钟
  document.addEventListener('visibilitychange', onVisible)
})

function onVisible() {
  if (document.visibilityState === 'visible') {
    refreshAll(false)
    scheduleRefresh()
  }
}

onUnmounted(() => {
  clearTimeout(timer)
  clearTimeout(searchTimer)
  document.removeEventListener('visibilitychange', onVisible)
})
</script>

<style scoped>
.stock-page {
  padding-bottom: var(--s-8);
}

/* ---------- 页头 ---------- */
.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--s-6);
  margin-bottom: var(--s-8);
}

.page-title {
  font-size: var(--t-3xl);
  letter-spacing: -0.03em;
}

.page-desc {
  margin-top: 8px;
  color: var(--c-text-muted);
  font-size: var(--t-sm);
  max-width: 46ch;
}

.page-head__side {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  flex-shrink: 0;
}

.refresh-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: var(--r-full);
  background: var(--c-surface-sunken);
  color: var(--c-text-muted);
  font-size: var(--t-xs);
  font-variant-numeric: tabular-nums;
}

.refresh-state .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--c-text-faint);
}

.refresh-state.is-live {
  background: color-mix(in srgb, var(--c-success) 12%, transparent);
  color: var(--c-success);
}

.refresh-state.is-live .dot {
  background: var(--c-success);
  animation: pulse 2s var(--ease) infinite;
}

@keyframes pulse {
  50% {
    opacity: 0.35;
  }
}

.refresh-btn svg {
  width: 15px;
  height: 15px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
}

/* 提醒入口：与「刷新」同层级但更轻，未授权时用品牌色才对得上「待办」的语义 */
.alert-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 34px;
  padding: 0 12px;
  border: 1px solid var(--c-border);
  border-radius: var(--r-full);
  background: var(--c-surface);
  color: var(--c-text-secondary);
  font-size: var(--t-sm);
  font-family: inherit;
  cursor: pointer;
  transition:
    background 120ms var(--ease),
    border-color 120ms var(--ease),
    color 120ms var(--ease);
}

.alert-btn svg {
  width: 15px;
  height: 15px;
  flex: none;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.9;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.alert-btn:hover:not(:disabled) {
  border-color: var(--c-brand);
  color: var(--c-brand);
}

/* 未授权：品牌色描边，提示这里有件事没做完 */
.alert-btn:not(.is-on):not(:disabled) {
  border-color: var(--c-brand-ring);
  color: var(--c-brand);
  background: var(--c-brand-soft);
}

/* 已开启：安静的实色，不再抢注意力 */
.alert-btn.is-on,
.alert-btn:disabled {
  cursor: default;
  color: var(--c-text-muted);
  background: var(--c-surface-sunken);
  border-color: transparent;
}

.alert-btn.is-on svg {
  color: var(--c-success);
}

.alert-btn:focus-visible {
  outline: 2px solid var(--c-brand);
  outline-offset: 2px;
}

.refresh-btn svg.spin {
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

/* ---------- 指数条 ---------- */
.indices {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: var(--s-3);
  margin-bottom: var(--s-8);
}

.index-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: var(--s-4);
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface);
}

.index-name {
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.index-price {
  font-size: var(--t-lg);
  font-weight: 620;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
}

.index-change {
  font-size: var(--t-xs);
  font-variant-numeric: tabular-nums;
}

/* ---------- 网格 ---------- */
.stock-grid {
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: var(--s-10);
}

/* 左栏拉满，自选股面板跟着撑高，避免侧栏很长时左侧出现断裂空白 */
.stock-main {
  display: flex;
  flex-direction: column;
  gap: var(--s-6);
}

.stock-main .panel {
  display: flex;
  flex-direction: column;
  flex: 1;
  /* 自选股较少时给个下限，视觉上与右侧热榜形成呼应而不是一个小条 */
  min-height: 300px;
}

.stock-main .empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}

/* ---------- 搜索 ---------- */
.stock-search {
  position: relative;
  display: flex;
  align-items: center;
  margin-bottom: var(--s-6);
}

.stock-search__icon {
  position: absolute;
  left: 14px;
  width: 17px;
  height: 17px;
  fill: none;
  stroke: var(--c-text-faint);
  stroke-width: 2;
  stroke-linecap: round;
  pointer-events: none;
}

.stock-search input {
  width: 100%;
  height: 46px;
  padding: 0 14px 0 42px;
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface);
  color: var(--c-text);
  font-size: var(--t-base);
  outline: none;
  transition: border-color 0.15s var(--ease), box-shadow 0.15s var(--ease);
}

.stock-search input::placeholder {
  color: var(--c-text-faint);
}

.stock-search input:focus {
  border-color: var(--c-brand);
  box-shadow: 0 0 0 3px var(--c-brand-ring);
}

.stock-search input::-webkit-search-cancel-button {
  -webkit-appearance: none;
}

.stock-search__hint {
  position: absolute;
  right: 14px;
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.stock-dropdown {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  right: 0;
  z-index: 30;
  margin: 0;
  padding: 6px;
  list-style: none;
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface);
  box-shadow: var(--e-lg);
  max-height: 320px;
  overflow-y: auto;
}

.stock-dropdown button {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  width: 100%;
  padding: 9px 10px;
  border: 0;
  border-radius: var(--r-sm);
  background: transparent;
  color: var(--c-text);
  text-align: left;
  cursor: pointer;
  transition: background-color 0.12s var(--ease);
}

.stock-dropdown button:hover {
  background: var(--c-surface-sunken);
}

.stock-dropdown__name {
  font-size: var(--t-sm);
  font-weight: 540;
}

.stock-dropdown__code {
  font-size: var(--t-xs);
  color: var(--c-text-faint);
  font-family: var(--f-mono);
  margin-left: auto;
}

.stock-dropdown__act {
  padding: 2px 8px;
  border-radius: var(--r-full);
  background: var(--c-brand-soft);
  color: var(--c-brand);
  font-size: var(--t-xs);
  white-space: nowrap;
}

.stock-dropdown__act.is-on {
  background: var(--c-surface-sunken);
  color: var(--c-text-faint);
}

/* ---------- 面板 ---------- */
.panel {
  border: 1px solid var(--c-border);
  border-radius: var(--r-lg);
  background: var(--c-surface);
  overflow: hidden;
}

.panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  padding: var(--s-4) var(--s-5);
  border-bottom: 1px solid var(--c-border);
}

.panel__title {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  font-size: var(--t-md);
  font-weight: 620;
}

.panel__count {
  display: inline-grid;
  place-items: center;
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: var(--r-full);
  background: var(--c-surface-sunken);
  color: var(--c-text-muted);
  font-size: var(--t-xs);
  font-weight: 600;
}

.panel__meta {
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.panel__error {
  padding: var(--s-10) var(--s-5);
  text-align: center;
  color: var(--c-text-muted);
  font-size: var(--t-sm);
}

.link-btn {
  padding: 0;
  border: 0;
  background: none;
  color: var(--c-text-muted);
  font-size: var(--t-xs);
  cursor: pointer;
}

.link-btn:hover {
  color: var(--c-brand);
}

/* ---------- 自选列表 ---------- */
.watch-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.watch-row {
  display: grid;
  /* 价格列右对齐贴住涨跌列，中间不留弹性空白 */
  grid-template-columns: minmax(0, 1fr) 28px;
  align-items: center;
  gap: var(--s-3);
  padding: 0 0 0 var(--s-5);
  border-bottom: 1px solid var(--c-border);
  transition: background-color 0.12s var(--ease);
}

.watch-row:last-child {
  border-bottom: 0;
}

.watch-row:hover {
  background: var(--c-surface-sunken);
}

/* 主体是按钮：整行可点进详情，删除按钮独立在外，避免嵌套按钮 */
.watch-row__main {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(88px, auto);
  align-items: center;
  gap: var(--s-5);
  padding: 12px 0;
  border: 0;
  background: none;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.watch-row__main:focus-visible {
  outline: 2px solid var(--c-brand);
  outline-offset: -2px;
  border-radius: var(--r-sm);
}

.watch-row__del {
  margin-right: var(--s-4);
}

.watch-row.is-suspended .watch-row__price {
  opacity: 0.55;
}

.watch-row__id {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.watch-row__name {
  font-size: var(--t-base);
  font-weight: 560;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.watch-row__code {
  font-family: var(--f-mono);
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.watch-row__price {
  display: flex;
  align-items: center;
  gap: 6px;
  justify-content: flex-end;
  font-size: var(--t-base);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.watch-row__chg {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  font-size: var(--t-xs);
  font-variant-numeric: tabular-nums;
  line-height: 1.5;
  white-space: nowrap;
}

.watch-row__del {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: var(--r-sm);
  background: transparent;
  color: var(--c-text-faint);
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.12s var(--ease), color 0.12s var(--ease),
    background-color 0.12s var(--ease);
}

.watch-row:hover .watch-row__del,
.watch-row__del:focus-visible {
  opacity: 1;
}

.watch-row__del:hover {
  background: var(--c-surface-sunken);
  color: var(--c-text);
}

.watch-row__del svg {
  width: 15px;
  height: 15px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
}

/* ---------- 热榜 ---------- */
.hot-list {
  margin: 0;
  padding: 0;
  list-style: none;
  max-height: 620px;
  overflow-y: auto;
}

.hot-row {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  padding: 10px var(--s-4);
  border-bottom: 1px solid var(--c-border);
}

.hot-row:last-child {
  border-bottom: 0;
}

.hot-row__rank {
  display: grid;
  place-items: center;
  width: 20px;
  height: 20px;
  border-radius: var(--r-sm);
  background: var(--c-surface-sunken);
  color: var(--c-text-muted);
  font-size: var(--t-xs);
  font-weight: 620;
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}

.hot-row__rank.is-top {
  background: var(--c-brand-soft);
  color: var(--c-brand);
}

.hot-row__main {
  flex: 1;
  min-width: 0;
}

/* 热榜主体也是按钮：整块可点，去掉按钮默认样式 */
.hot-row__main--btn {
  padding: 0;
  border: 0;
  background: none;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.hot-row__main--btn:focus-visible {
  outline: 2px solid var(--c-brand);
  outline-offset: 2px;
  border-radius: var(--r-sm);
}

.hot-row__name {
  transition: color 0.12s var(--ease);
}

.hot-row__main--btn:hover .hot-row__name {
  color: var(--c-brand);
}

.hot-row__top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-2);
}

.hot-row__name {
  font-size: var(--t-sm);
  font-weight: 540;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.hot-row__chg {
  font-size: var(--t-xs);
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}

.hot-row__sub {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 2px;
}

.hot-row__code {
  font-family: var(--f-mono);
  font-size: 10.5px;
  color: var(--c-text-faint);
}

.hot-row__sub .chip {
  padding: 1px 7px;
  font-size: 10.5px;
}

.hot-row__add {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border: 1px solid var(--c-border);
  border-radius: var(--r-full);
  background: transparent;
  color: var(--c-text-muted);
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
  flex-shrink: 0;
  transition: border-color 0.12s var(--ease), color 0.12s var(--ease);
}

.hot-row__add:hover:not(:disabled) {
  border-color: var(--c-brand);
  color: var(--c-brand);
}

.hot-row__add:disabled {
  border-color: transparent;
  color: var(--c-success);
  cursor: default;
}

.hot-skeleton__row {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  padding: 12px var(--s-4);
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

/* ---------- 模态 ---------- */
.modal {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: grid;
  place-items: center;
  padding: var(--s-5);
  background: var(--c-overlay);
  backdrop-filter: blur(2px);
}

.modal__box {
  width: 100%;
  max-width: 380px;
  padding: var(--s-6);
  border-radius: var(--r-lg);
  background: var(--c-surface);
  box-shadow: var(--e-lg);
}

.modal__title {
  font-size: var(--t-lg);
}

.modal__desc {
  margin-top: var(--s-3);
  color: var(--c-text-muted);
  font-size: var(--t-sm);
}

.modal__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--s-3);
  margin-top: var(--s-6);
}

/* ---------- 过渡 ---------- */
.drop-enter-active,
.drop-leave-active {
  transition: opacity 0.15s var(--ease), transform 0.15s var(--ease);
}

.drop-enter-from,
.drop-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.18s var(--ease);
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

/* ---------- 响应式 ---------- */
@media (max-width: 1040px) {
  .stock-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .indices {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .page-head {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--s-4);
  }

  .page-head__side {
    width: 100%;
    /* 一行放不下时让「开启通知」折到下一行，而不是把三个控件挤成一团 */
    flex-wrap: wrap;
    gap: var(--s-2);
    justify-content: flex-start;
  }

  .alert-btn,
  .refresh-btn {
    min-height: 44px;
  }

  .indices {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--s-2);
  }

  .index-card {
    padding: var(--s-3);
  }

  .watch-row {
    grid-template-columns: minmax(0, 1fr) 28px;
    gap: var(--s-2);
  }

  .watch-row__main {
    /* 窄屏去掉涨跌额那一列，只保留价格 + 涨跌幅 */
    grid-template-columns: minmax(0, 1fr) auto minmax(60px, auto);
    gap: var(--s-3);
  }

  .watch-row__chg span:first-child {
    display: none;
  }

  .watch-row__del {
    opacity: 1;
  }
}
</style>
