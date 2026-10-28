<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { fetchIndustry, fetchQuotes, type StockQuote } from '@/api/stock'
import { usePositions } from '@/composables/usePositions'
import { analyzePortfolio, assetClassOf, type PortfolioInput, type PortfolioRow } from '@/utils/portfolio'
import { fmtNum, fmtSigned, toneClass } from '@/utils/stockFormat'

/**
 * 持仓组合分析页。
 *
 * <p>这里刻意只做「取数 + 展示」：所有口径计算都在 `@/utils/portfolio` 的纯函数里完成，
 * 页面自己再算一遍「当日盈亏」之类，迟早会和纯函数对不上。</p>
 *
 * <p>两个容易出错的地方在页面上显式披露，而不是悄悄吞掉：</p>
 * <ul>
 *   <li>缺昨收的持仓算不出当日盈亏 —— 合计里排除并标「N 只未计」，
 *       如果直接按 0 计入，会把「今天没数据」显示成「今天不赚不亏」。</li>
 *   <li>缺行业标签同理：单列覆盖率，不能并进「无行业」，
 *       否则行业集中度会在数据不全时给出错误结论。</li>
 * </ul>
 */

/** 与桌面版 stock-analysis-prh 的缓存键保持独立，避免互相污染 */
const INDUSTRY_CACHE_KEY = 'iblog-portfolio-industry-cache'
/** 行业会变（更名/并购/重新分类），30 天后重拉一次 */
const INDUSTRY_TTL_MS = 30 * 24 * 60 * 60 * 1000
/** 首屏只补前 20 只：行业是逐只接口，全量串行会把首屏拖到十几秒 */
const INDUSTRY_BATCH = 20
const FX_RATE_KEY = 'iblog-portfolio-fx-rate'

interface IndustryCacheEntry {
  name: string
  at: number
}
type IndustryCache = Record<string, IndustryCacheEntry>

function readIndustryCacheRaw(): IndustryCache {
  try {
    const raw = localStorage.getItem(INDUSTRY_CACHE_KEY)
    if (!raw) {
      return {}
    }
    const parsed: unknown = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object') {
      return {}
    }
    const out: IndustryCache = {}
    const now = Date.now()
    for (const [code, value] of Object.entries(parsed as Record<string, unknown>)) {
      const entry = value as Partial<IndustryCacheEntry> | null
      if (!entry || typeof entry.name !== 'string' || !entry.name) {
        continue
      }
      if (typeof entry.at !== 'number' || now - entry.at > INDUSTRY_TTL_MS) {
        continue
      }
      out[code] = { name: entry.name, at: entry.at }
    }
    return out
  } catch {
    /* 隐私模式 / 脏数据：当作没有缓存，重新拉即可 */
    return {}
  }
}

function loadIndustryNames(): Record<string, string> {
  const cache = readIndustryCacheRaw()
  const out: Record<string, string> = {}
  for (const [code, entry] of Object.entries(cache)) {
    out[code] = entry.name
  }
  return out
}

function writeIndustryCache(cache: IndustryCache) {
  try {
    localStorage.setItem(INDUSTRY_CACHE_KEY, JSON.stringify(cache))
  } catch {
    /* 配额满就放弃缓存：行业只是增强信息，下次重拉不会影响主面板 */
  }
}

function loadFxRate(): number {
  try {
    const n = Number(localStorage.getItem(FX_RATE_KEY))
    return Number.isFinite(n) && n > 0 ? n : 1
  } catch {
    return 1
  }
}

function errText(err: unknown): string {
  return err instanceof Error ? err.message : String(err)
}

const { positions } = usePositions()

const quotes = ref<Record<string, StockQuote>>({})
const industries = ref<Record<string, string>>(loadIndustryNames())
/** 汇率由用户填：系统不内置汇率源，写死一个再显示到分位比不给更糟 */
const fxRate = ref(loadFxRate())
const loading = ref(true)
const refreshing = ref(false)
/** 本次刷新失败但保留上一次数据时为 true，界面标「数据延迟」而不是清空 */
const stale = ref(false)
const error = ref('')
const industryLoading = ref(false)
const industryProgress = ref({ done: 0, total: 0 })

/** 竞态守卫：旧请求回来时序号已变，直接丢弃，避免切持仓后闪回旧行情 */
let quoteSeq = 0
let industrySeq = 0

const inputs = computed<PortfolioInput[]>(() =>
  positions.value.map((p) => {
    const quote = quotes.value[p.code]
    const quotePrice = Number(quote?.price)
    const snapshotPrice = Number(p.price)
    // 行情缺失时用最近一次快照、再退到成本价：宁可展示「盈亏 0」也不要 NaN
    const currentPrice =
      Number.isFinite(quotePrice) && quotePrice > 0
        ? quotePrice
        : Number.isFinite(snapshotPrice) && snapshotPrice > 0
          ? snapshotPrice
          : Number(p.buyPrice) || 0
    const quotePrev = Number(quote?.prevClose)
    const snapshotPrev = Number(p.prevClose)
    const prevClose = quotePrev > 0 ? quotePrev : snapshotPrev > 0 ? snapshotPrev : null
    const pe = quote && Number.isFinite(Number(quote.pe)) ? Number(quote.pe) : null
    return {
      code: p.code,
      name: quote?.name || p.name,
      quantity: Number(p.quantity) || 0,
      buyPrice: Number(p.buyPrice) || 0,
      currentPrice,
      prevClose,
      pe,
      // 港股没有行业分类，传空串让它归入「未知」而不是臆造一个行业
      industry: industries.value[p.code] ?? ''
    }
  })
)

const analysis = computed(() => analyzePortfolio(inputs.value, { fxRate: fxRate.value }))

/** 条形图与表格都按权重降序：一眼先看到重仓 */
const weightRows = computed(() =>
  [...analysis.value.rows].sort((a, b) => b.weightPct - a.weightPct)
)

const topIndustryGroups = computed(() => analysis.value.byIndustry.groups.slice(0, 8))

const hkCount = computed(() => analysis.value.rows.filter((r) => r.assetClass === '港股').length)

/** 还能补行业的代码：港股不算缺失，补也补不出来 */
const pendingIndustryCodes = computed(() =>
  positions.value
    .map((p) => p.code)
    .filter((code) => assetClassOf(code) !== '港股' && !industries.value[code])
)

const topHoldingText = computed(() => {
  const top = analysis.value.topHolding
  return top ? `${top.name || top.code} · ${fmtNum(top.weightPct, 1)}%` : '--'
})

function money(v: number | null | undefined): string {
  return v == null || Number.isNaN(v) ? '--' : `¥${fmtNum(v, 2)}`
}

function signedMoney(v: number | null | undefined): string {
  if (v == null || Number.isNaN(v)) {
    return '--'
  }
  return v >= 0 ? `+¥${fmtNum(Math.abs(v), 2)}` : `-¥${fmtNum(Math.abs(v), 2)}`
}

function pctText(v: number | null | undefined): string {
  return v == null || Number.isNaN(v) ? '--' : `${fmtSigned(v)}%`
}

function bestWorstText(row: PortfolioRow | null): string {
  if (!row) {
    return '--'
  }
  return `${row.name || row.code} ${signedMoney(row.dayProfitCNY)}（${pctText(row.dayChangePct)}）`
}

/** 港股报价通常三位小数，A 股两位；用一个函数收口，避免表格里两处不一致 */
function priceDigits(row: PortfolioRow): number {
  return row.assetClass === '港股' ? 3 : 2
}

function barWidth(pct: number): string {
  return `${Math.min(100, Math.max(0, pct))}%`
}

async function loadQuotes(initial: boolean) {
  const codes = positions.value.map((p) => p.code)
  if (codes.length === 0) {
    quotes.value = {}
    return
  }
  const my = ++quoteSeq
  try {
    const list = await fetchQuotes(codes)
    if (my !== quoteSeq) {
      return
    }
    const next: Record<string, StockQuote> = {}
    for (const q of list) {
      if (q?.code) {
        next[q.code] = q
      }
    }
    quotes.value = next
    stale.value = false
    error.value = ''
  } catch (err) {
    if (my !== quoteSeq) {
      return
    }
    // 保留旧 quotes：行情接口偶发超时不应该把整页清空重来
    stale.value = true
    if (initial && Object.keys(quotes.value).length === 0) {
      error.value = `行情获取失败：${errText(err)}`
    }
  }
}

async function loadIndustries(initial: boolean) {
  if (industryLoading.value) {
    return
  }
  const allCodes = positions.value.map((p) => p.code)
  const scope = initial ? allCodes.slice(0, INDUSTRY_BATCH) : allCodes
  const targets = scope.filter((code) => assetClassOf(code) !== '港股' && !industries.value[code])
  if (targets.length === 0) {
    return
  }
  const my = ++industrySeq
  industryLoading.value = true
  industryProgress.value = { done: 0, total: targets.length }
  const cache = readIndustryCacheRaw()
  for (let i = 0; i < targets.length; i += 1) {
    if (my !== industrySeq) {
      break
    }
    const code = targets[i]
    try {
      const data = await fetchIndustry(code)
      const name = String(data?.industryName ?? '').trim()
      // 后端对港股返回 '港股'、无数据返回空串，两者都不算真实行业标签
      if (name && name !== '港股') {
        industries.value = { ...industries.value, [code]: name }
        cache[code] = { name, at: Date.now() }
      }
    } catch {
      /* 单只失败静默跳过：行业只是增强信息，不该打断其余持仓，也不该弹错误 */
    }
    if (my === industrySeq) {
      industryProgress.value = { done: i + 1, total: targets.length }
    }
  }
  writeIndustryCache(cache)
  if (my === industrySeq) {
    industryLoading.value = false
  }
}

async function refresh(initial = false) {
  if (refreshing.value) {
    return
  }
  refreshing.value = true
  if (initial) {
    loading.value = true
  }
  try {
    await loadQuotes(initial)
    await loadIndustries(initial)
  } finally {
    if (initial) {
      loading.value = false
    }
    refreshing.value = false
  }
}

function fillIndustries() {
  void loadIndustries(false)
}

watch(fxRate, (value) => {
  try {
    localStorage.setItem(FX_RATE_KEY, String(value || 1))
  } catch {
    /* ignore */
  }
})

onMounted(() => {
  void refresh(true)
})
</script>

<template>
  <div class="portfolio-page">
    <div class="layout">
      <header class="page-head">
        <div>
          <h1 class="page-title">持仓组合</h1>
          <p class="page-desc">
            把逐只盈亏升级成组合视角：仓位权重、集中度、当日涨跌结构、行业分布与加权 PE。
          </p>
        </div>
        <div class="page-head__side">
          <span v-if="stale" class="data-flag" title="本次刷新失败，展示的是上一次成功的数据">
            数据延迟
          </span>
          <label v-if="analysis.hasHK" class="fx-field">
            <span>港币汇率</span>
            <input
              v-model.number="fxRate"
              class="fx-field__input"
              type="number"
              min="0.01"
              step="0.01"
              inputmode="decimal"
              aria-label="港币兑人民币汇率"
            />
          </label>
          <button type="button" class="btn btn--ghost" :disabled="refreshing" @click="refresh()">
            {{ refreshing ? '刷新中…' : '刷新行情' }}
          </button>
        </div>
      </header>

      <p v-if="error" class="page-error">{{ error }}</p>

      <div v-if="loading && analysis.isEmpty" class="loading-state">正在读取持仓与行情…</div>

      <div v-else-if="analysis.isEmpty" class="empty-state">
        <svg class="empty-state__icon" viewBox="0 0 24 24" aria-hidden="true">
          <rect x="3" y="4" width="18" height="16" rx="2" />
          <path d="M3 9h18M8 4v16" />
        </svg>
        <p class="empty-title">还没有持仓记录</p>
        <p class="empty-desc">在行情页搜索个股并加入持仓后，这里会自动汇总组合指标。</p>
        <RouterLink to="/stock" class="btn">去行情页添加持仓</RouterLink>
      </div>

      <div v-else class="stack">
        <p v-if="analysis.fxWarning" class="warn-bar">{{ analysis.fxWarning }}</p>

        <section class="summary-grid" aria-label="组合摘要">
          <article class="card">
            <p class="card__label">组合总市值</p>
            <p class="card__value">{{ money(analysis.totalMarketValueCNY) }}</p>
            <p class="card__hint">{{ analysis.count }} 只 · 成本 {{ money(analysis.totalCostCNY) }}</p>
          </article>
          <article class="card">
            <p class="card__label">总浮动盈亏</p>
            <p class="card__value" :class="toneClass(analysis.totalProfitCNY)">
              {{ signedMoney(analysis.totalProfitCNY) }}
            </p>
            <p class="card__hint" :class="toneClass(analysis.totalProfitPctCNY)">
              {{ pctText(analysis.totalProfitPctCNY) }}
            </p>
          </article>
          <article class="card">
            <p class="card__label">今日盈亏</p>
            <p class="card__value" :class="toneClass(analysis.dayProfit.value)">
              {{ signedMoney(analysis.dayProfit.value) }}
            </p>
            <p class="card__hint" :class="toneClass(analysis.dayProfit.pct)">
              {{ pctText(analysis.dayProfit.pct) }}
            </p>
            <!-- 缺昨收的持仓不进合计：不标注的话，用户会以为这 N 只今天刚好不赚不亏 -->
            <p v-if="analysis.dayProfit.missing > 0" class="card__note">
              {{ analysis.dayProfit.missing }} 只未计（缺昨收）
            </p>
          </article>
        </section>

        <section class="two-col">
          <article class="card">
            <h2 class="card__title">集中度风险</h2>
            <dl class="kv">
              <div class="kv__row">
                <dt>最大单票权重</dt>
                <dd>{{ topHoldingText }}</dd>
              </div>
              <div class="kv__row">
                <dt>前三大合计</dt>
                <dd>{{ fmtNum(analysis.top3WeightPct, 1) }}%</dd>
              </div>
              <div class="kv__row">
                <dt>HHI</dt>
                <dd>{{ fmtNum(analysis.hhi, 0) }} · {{ analysis.concentration.level }}</dd>
              </div>
            </dl>
            <p class="card__note">{{ analysis.concentration.hint }}</p>
            <p class="card__note">HHI 为各持仓权重平方和（0–10000），数值越高越集中。</p>
          </article>

          <article class="card">
            <h2 class="card__title">当日涨跌分布</h2>
            <div class="breadth">
              <span class="breadth__item tone-up">上涨 {{ analysis.dayBreadth.up }}</span>
              <span class="breadth__item tone-down">下跌 {{ analysis.dayBreadth.down }}</span>
              <span class="breadth__item tone-flat">平盘 {{ analysis.dayBreadth.flat }}</span>
            </div>
            <dl class="kv">
              <div class="kv__row">
                <dt>今日最赚</dt>
                <dd :class="toneClass(analysis.dayBestWorst.best?.dayProfitCNY ?? null)">
                  {{ bestWorstText(analysis.dayBestWorst.best) }}
                </dd>
              </div>
              <div class="kv__row">
                <dt>今日最亏</dt>
                <dd :class="toneClass(analysis.dayBestWorst.worst?.dayProfitCNY ?? null)">
                  {{ bestWorstText(analysis.dayBestWorst.worst) }}
                </dd>
              </div>
            </dl>
            <p class="card__note">缺昨收的持仓不参与涨跌家数与当日盈亏统计。</p>
          </article>
        </section>

        <section class="card">
          <h2 class="card__title">持仓权重</h2>
          <div class="weight-list">
            <div v-for="row in weightRows" :key="row.code" class="weight-row">
              <span class="weight-row__name" :title="`${row.name || row.code}（${row.assetClass}）`">
                {{ row.name || row.code }}
              </span>
              <span class="weight-row__bar" aria-hidden="true">
                <i class="weight-row__fill" :style="{ width: barWidth(row.weightPct) }" />
              </span>
              <span class="weight-row__pct">{{ fmtNum(row.weightPct, 1) }}%</span>
            </div>
          </div>
        </section>

        <section class="card">
          <div class="card__head">
            <h2 class="card__title">行业分布</h2>
            <span class="card__extra">
              行业标签 已覆盖 {{ analysis.byIndustry.knownCount }}/{{ analysis.count }}（{{
                fmtNum(analysis.byIndustry.coveragePct, 0)
              }}%）
            </span>
          </div>

          <div v-if="topIndustryGroups.length" class="industry-list">
            <div v-for="group in topIndustryGroups" :key="group.key" class="industry-row">
              <span class="industry-row__name">{{ group.key }}</span>
              <span class="industry-row__bar" aria-hidden="true">
                <i class="industry-row__fill" :style="{ width: barWidth(group.portfolioPct) }" />
              </span>
              <span class="industry-row__meta">
                {{ group.count }} 只 · 组内 {{ fmtNum(group.weightPct, 1) }}% · 占组合
                {{ fmtNum(group.portfolioPct, 1) }}%
              </span>
            </div>
          </div>
          <p v-else class="card__note card__note--strong">
            尚未获取行业标签，缺失的部分不会被算成「无行业」。
          </p>

          <p v-if="analysis.byIndustry.unknownCount > 0" class="card__note">
            还有 {{ analysis.byIndustry.unknownCount }} 只没有行业标签<template v-if="hkCount > 0">
              （其中港股 {{ hkCount }} 只，港股不参与行业分类）</template
            >。
          </p>

          <button
            v-if="pendingIndustryCodes.length"
            type="button"
            class="btn btn--ghost industry-fill"
            :disabled="industryLoading"
            @click="fillIndustries()"
          >
            {{
              industryLoading
                ? `补齐中… ${industryProgress.done}/${industryProgress.total}`
                : `补齐行业（${pendingIndustryCodes.length} 只）`
            }}
          </button>
          <p class="card__note">行业标签来自个股接口，逐只串行拉取，结果缓存 30 天。</p>
        </section>

        <section class="card">
          <h2 class="card__title">加权 PE</h2>
          <p class="card__value">
            {{ analysis.weightedPE.value == null ? '--' : fmtNum(analysis.weightedPE.value, 1) }}
          </p>
          <p class="card__note">
            仅统计 PE&gt;0 的 {{ analysis.weightedPE.covered }} 只，剔除
            {{ analysis.weightedPE.excluded }} 只（亏损或缺失）。权重按人民币市值。
          </p>
        </section>

        <section class="card card--table">
          <div class="card__head">
            <h2 class="card__title">持仓明细</h2>
            <span class="card__count">{{ analysis.count }}</span>
          </div>
          <div class="table-wrap">
            <table class="table">
              <thead>
                <tr>
                  <th scope="col">代码/名称</th>
                  <th scope="col" class="num">数量</th>
                  <th scope="col" class="num">成本价</th>
                  <th scope="col" class="num">现价</th>
                  <th scope="col" class="num">今涨跌</th>
                  <th scope="col" class="num">市值(元)</th>
                  <th scope="col" class="num">浮动盈亏</th>
                  <th scope="col" class="num">权重</th>
                  <th scope="col">行业</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in weightRows" :key="row.code">
                  <td>
                    <div class="cell-name">
                      <span class="cell-name__title">{{ row.name || row.code }}</span>
                      <span class="cell-name__code">{{ row.code }}</span>
                    </div>
                  </td>
                  <td class="num">{{ fmtNum(row.quantity, 0) }}</td>
                  <td class="num">{{ fmtNum(row.buyPrice, priceDigits(row)) }}</td>
                  <td class="num">{{ fmtNum(row.currentPrice, priceDigits(row)) }}</td>
                  <td class="num" :class="toneClass(row.dayChangePct)">{{ pctText(row.dayChangePct) }}</td>
                  <td class="num">{{ fmtNum(row.marketValueCNY, 2) }}</td>
                  <td class="num" :class="toneClass(row.profitCNY)">{{ signedMoney(row.profitCNY) }}</td>
                  <td class="num">{{ fmtNum(row.weightPct, 1) }}%</td>
                  <!-- 未取到行业显示 --：留空会让人以为这只票没有行业 -->
                  <td>{{ row.industryKnown ? row.industry : '--' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <p class="page-note">金额默认人民币；港股按汇率 1:1 估算（如未设置）。本页不构成投资建议。</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.portfolio-page {
  padding-bottom: var(--s-8);
}

.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--s-4) var(--s-6);
  margin-bottom: var(--s-6);
}

.page-title {
  margin: 0;
  font-size: var(--t-3xl);
  font-weight: 700;
  letter-spacing: -0.03em;
  color: var(--c-text);
}

.page-desc {
  margin: 8px 0 0;
  max-width: 46ch;
  font-size: var(--t-sm);
  color: var(--c-text-muted);
}

.page-head__side {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--s-3);
}

.data-flag {
  padding: 0 6px;
  line-height: 18px;
  font-size: var(--t-xs);
  color: var(--c-accent);
  background: color-mix(in srgb, var(--c-accent) 8%, transparent);
  border: 1px solid color-mix(in srgb, var(--c-accent) 45%, transparent);
  border-radius: var(--r-sm);
}

.fx-field {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.fx-field__input {
  width: 78px;
  padding: 5px 8px;
  font: inherit;
  font-variant-numeric: tabular-nums;
  color: var(--c-text);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-sm);
}

.fx-field__input:focus-visible {
  outline: 2px solid var(--c-brand-ring);
  outline-offset: 1px;
}

.page-error {
  margin: 0 0 var(--s-4);
  font-size: var(--t-sm);
  color: var(--stock-up);
}

.loading-state {
  padding: var(--s-12) 0;
  text-align: center;
  font-size: var(--t-sm);
  color: var(--c-text-muted);
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--s-3);
  padding: var(--s-16) var(--s-6);
  text-align: center;
}

.empty-state .btn {
  margin-top: var(--s-2);
  text-decoration: none;
}

.empty-title {
  margin: 0;
  font-size: var(--t-md);
  font-weight: 600;
  color: var(--c-text);
}

.empty-desc {
  margin: 0;
  max-width: 40ch;
  font-size: var(--t-sm);
  color: var(--c-text-muted);
}

.stack {
  display: flex;
  flex-direction: column;
  gap: var(--s-4);
}

.warn-bar {
  margin: 0;
  padding: var(--s-3) var(--s-4);
  font-size: var(--t-sm);
  color: var(--c-text-secondary);
  background: color-mix(in srgb, var(--c-accent) 8%, transparent);
  border: 1px solid color-mix(in srgb, var(--c-accent) 40%, transparent);
  border-radius: var(--r-md);
}

.card {
  display: flex;
  flex-direction: column;
  gap: var(--s-3);
  padding: var(--s-5);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-md);
}

.card__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--s-2) var(--s-3);
}

.card__title {
  margin: 0;
  font-size: var(--t-base);
  font-weight: 600;
  color: var(--c-text);
}

.card__label {
  margin: 0;
  font-size: var(--t-sm);
  color: var(--c-text-muted);
}

.card__value {
  margin: 0;
  font-size: var(--t-2xl);
  font-weight: 700;
  letter-spacing: -0.02em;
  font-variant-numeric: tabular-nums;
}

.card__hint {
  margin: 0;
  font-size: var(--t-sm);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.card__note {
  margin: 0;
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.card__note--strong {
  font-size: var(--t-sm);
  color: var(--c-text-secondary);
}

.card__extra {
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.card__count {
  padding: 0 7px;
  font-size: var(--t-xs);
  color: var(--c-text-muted);
  background: var(--c-surface-sunken);
  border-radius: var(--r-full);
  font-variant-numeric: tabular-nums;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--s-4);
}

.two-col {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s-4);
}

.kv {
  display: flex;
  flex-direction: column;
  gap: var(--s-2);
  margin: 0;
}

.kv__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-3);
}

.kv__row dt {
  font-size: var(--t-sm);
  color: var(--c-text-muted);
}

.kv__row dd {
  margin: 0;
  font-size: var(--t-sm);
  font-weight: 600;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.breadth {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2);
}

.breadth__item {
  padding: 2px 8px;
  font-size: var(--t-xs);
  font-weight: 600;
  border-radius: var(--r-full);
  background: var(--c-surface-sunken);
}

.breadth__item.tone-up {
  background: color-mix(in srgb, var(--stock-up) 12%, transparent);
}

.breadth__item.tone-down {
  background: color-mix(in srgb, var(--stock-down) 12%, transparent);
}

.weight-list,
.industry-list {
  display: flex;
  flex-direction: column;
  gap: var(--s-2);
}

.weight-row {
  display: grid;
  grid-template-columns: minmax(80px, 140px) minmax(0, 1fr) 56px;
  align-items: center;
  gap: var(--s-3);
  font-size: var(--t-sm);
  font-variant-numeric: tabular-nums;
}

.weight-row__name,
.industry-row__name {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  color: var(--c-text-secondary);
}

.weight-row__bar,
.industry-row__bar {
  position: relative;
  height: 8px;
  overflow: hidden;
  background: var(--c-surface-sunken);
  border-radius: 4px;
}

.weight-row__fill {
  display: block;
  height: 100%;
  background: var(--c-brand);
  border-radius: 4px;
  transition: width var(--ease) 220ms;
}

.weight-row__pct {
  text-align: right;
  font-weight: 600;
}

.industry-row {
  display: grid;
  grid-template-columns: minmax(72px, 120px) minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--s-3);
  font-size: var(--t-sm);
  font-variant-numeric: tabular-nums;
}

.industry-row__fill {
  display: block;
  height: 100%;
  background: color-mix(in srgb, var(--c-brand) 70%, transparent);
  border-radius: 4px;
  transition: width var(--ease) 220ms;
}

.industry-row__meta {
  font-size: var(--t-xs);
  color: var(--c-text-muted);
  text-align: right;
}

.industry-fill {
  align-self: flex-start;
}

.card--table {
  gap: var(--s-4);
}

.table-wrap {
  overflow-x: auto;
}

.table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--t-sm);
}

.table th,
.table td {
  padding: 10px var(--s-3);
  text-align: left;
  white-space: nowrap;
  border-bottom: 1px solid var(--c-border);
}

.table th {
  font-size: var(--t-xs);
  font-weight: 600;
  color: var(--c-text-muted);
}

.table tbody tr:last-child td {
  border-bottom: none;
}

.table .num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.cell-name {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.cell-name__title {
  font-weight: 600;
  color: var(--c-text);
}

.cell-name__code {
  font-family: var(--f-mono);
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.page-note {
  margin: 0;
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

/* 720px 以下全部单列：摘要、风险/涨跌、行业、权重都在窄屏堆叠 */
@media (max-width: 720px) {
  .summary-grid,
  .two-col {
    grid-template-columns: minmax(0, 1fr);
  }

  .card {
    padding: var(--s-4);
  }

  .page-title {
    font-size: var(--t-2xl);
  }

  .page-head {
    align-items: flex-start;
  }

  .weight-row {
    grid-template-columns: minmax(64px, 110px) minmax(0, 1fr) 52px;
    gap: var(--s-2);
  }

  .industry-row {
    grid-template-columns: minmax(60px, 96px) minmax(0, 1fr);
    gap: var(--s-2);
  }

  .industry-row__meta {
    grid-column: 1 / -1;
    text-align: left;
  }
}
</style>
