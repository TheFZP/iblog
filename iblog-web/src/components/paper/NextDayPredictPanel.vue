<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  fetchPredictDaily,
  fetchPredictDates,
  fetchPredictStats,
  type PredictDaily,
  type PredictDateItem,
  type PredictItem,
  type PredictStats
} from '@/api/predict'

/**
 * 次日看涨榜：交易日 15:30 全市场扫描的预测结果 + 近 N 日准确率。
 *
 * <p>主指标是可交易口径（T+1 开盘买 → 收盘卖），收盘对收盘只作参考；
 * 待评估的预测不计入准确率分母。</p>
 */

const STAT_DAYS = 30
const REFRESH_MS = 60_000

const daily = ref<PredictDaily | null>(null)
const dates = ref<PredictDateItem[]>([])
const stats = ref<PredictStats | null>(null)
const selectedDate = ref('')
const days = ref(STAT_DAYS)
const loading = ref(true)
const statsLoading = ref(true)
const error = ref('')
let timer: ReturnType<typeof setInterval> | undefined

const items = computed(() => daily.value?.items ?? [])
const latestDate = computed(() => dates.value[0]?.date ?? '')

const hitRateText = computed(() => {
  const value = stats.value
  if (!value || value.evaluated === 0) return '--'
  return `${value.hitRatePct.toFixed(1)}%`
})

const closeHitRateText = computed(() => {
  const value = stats.value
  if (!value || value.evaluated === 0) return '--'
  return `${value.hitCloseRatePct.toFixed(1)}%`
})

function pct(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '--'
  return `${value > 0 ? '+' : ''}${value.toFixed(2)}%`
}

function signedClass(value: number | null | undefined): string {
  if (value === null || value === undefined || value === 0) return ''
  return value > 0 ? 'is-up' : 'is-down'
}

function statusText(item: PredictItem): string {
  if (item.evalStatus === 'NO_DATA') return '无数据'
  if (item.evalStatus !== 'EVALUATED') return '待评估'
  if (item.hit === 1) return '命中'
  return '未命中'
}

function statusClass(item: PredictItem): string {
  if (item.evalStatus === 'NO_DATA') return 'is-muted'
  if (item.evalStatus !== 'EVALUATED') return 'is-pending'
  return item.hit === 1 ? 'is-hit' : 'is-miss'
}

function probSourceText(item: PredictItem): string {
  return item.probSource === 'CALIBRATED'
    ? `校准 · ${item.calibrationN} 样本`
    : '参考概率'
}

function safeUrl(url: string): string {
  return url.startsWith('http://') || url.startsWith('https://') ? url : ''
}

/** 榜单里最多展示两条带链接的新闻，避免整行被标题撑爆 */
function visibleNews(item: PredictItem) {
  return item.newsSources.filter((news) => safeUrl(news.url)).slice(0, 2)
}

async function loadDaily(date = '') {
  try {
    const data = await fetchPredictDaily(date || undefined)
    daily.value = data
    error.value = ''
    if (!date && data.date) selectedDate.value = data.date
  } catch (err) {
    error.value = err instanceof Error ? err.message : '预测榜单加载失败'
  }
}

async function loadDates() {
  try {
    dates.value = await fetchPredictDates(30)
  } catch {
    // 日期列表失败不影响主榜单
  }
}

async function loadStats() {
  statsLoading.value = true
  try {
    stats.value = await fetchPredictStats(days.value)
  } catch {
    // 统计失败时保留旧数据
  } finally {
    statsLoading.value = false
  }
}

async function loadAll() {
  loading.value = true
  await Promise.all([loadDaily(selectedDate.value), loadDates(), loadStats()])
  loading.value = false
}

watch(selectedDate, (value) => {
  if (value) void loadDaily(value)
})

async function refresh() {
  await Promise.all([loadDaily(selectedDate.value), loadDates(), loadStats()])
}

onMounted(() => {
  void loadAll().finally(() => {
    timer = setInterval(() => {
      if (document.visibilityState === 'visible') void refresh()
    }, REFRESH_MS)
  })
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <section class="panel ndp" aria-label="次日看涨榜">
    <div class="ndp__head">
      <h2 class="ndp__title">
        次日看涨
        <span class="ndp__count">{{ items.length }}</span>
      </h2>
      <div class="ndp__controls">
        <select v-if="dates.length" v-model="selectedDate" class="ndp__select" aria-label="选择预测日期">
          <option v-for="item in dates" :key="item.date" :value="item.date">
            {{ item.date }}（{{ item.count }} 只）
          </option>
        </select>
        <span v-else class="ndp__meta">尚无历史榜单</span>
        <span v-if="daily?.generatedAt" class="ndp__meta">生成于 {{ daily.generatedAt }}</span>
      </div>
    </div>

    <div class="ndp__stats">
      <div class="ndp__stat">
        <span class="ndp__stat-label">近 {{ days }} 日可交易胜率</span>
        <b class="ndp__stat-value">{{ hitRateText }}</b>
        <span class="ndp__stat-sub">{{ stats?.hits ?? 0 }} / {{ stats?.evaluated ?? 0 }} 次</span>
      </div>
      <div class="ndp__stat">
        <span class="ndp__stat-label">平均开盘→收盘</span>
        <b class="ndp__stat-value" :class="signedClass(stats?.avgOpenReturnPct)">
          {{ pct(stats?.avgOpenReturnPct) }}
        </b>
        <span class="ndp__stat-sub">T+1 可交易口径</span>
      </div>
      <div class="ndp__stat">
        <span class="ndp__stat-label">收盘对收盘胜率</span>
        <b class="ndp__stat-value">{{ closeHitRateText }}</b>
        <span class="ndp__stat-sub">仅参考</span>
      </div>
      <div class="ndp__stat">
        <span class="ndp__stat-label">平均收盘收益</span>
        <b class="ndp__stat-value" :class="signedClass(stats?.avgNextReturnPct)">
          {{ pct(stats?.avgNextReturnPct) }}
        </b>
        <span class="ndp__stat-sub">T+1 收盘 vs T 收盘</span>
      </div>
    </div>

    <div class="ndp__buckets">
      <template v-if="stats?.byScore?.length">
        <span class="ndp__bucket-label">分数档</span>
        <span v-for="bucket in stats.byScore" :key="`s-${bucket.label}`" class="ndp__chip">
          {{ bucket.label }}分
          <b>{{ bucket.evaluated ? `${bucket.hitRatePct.toFixed(0)}%` : '--' }}</b>
        </span>
      </template>
      <template v-if="stats?.byProbability?.length">
        <span class="ndp__bucket-label">概率档</span>
        <span v-for="bucket in stats.byProbability" :key="`p-${bucket.label}`" class="ndp__chip">
          {{ bucket.label }}%
          <b>{{ bucket.evaluated ? `${bucket.hitRatePct.toFixed(0)}%` : '--' }}</b>
        </span>
      </template>
      <span v-if="statsLoading" class="ndp__meta">统计加载中…</span>
    </div>

    <div class="table-wrap">
      <table v-if="items.length" class="ndp__table">
        <thead>
          <tr>
            <th scope="col" class="num">排名</th>
            <th scope="col">股票</th>
            <th scope="col" class="num">评分</th>
            <th scope="col" class="num">参考概率</th>
            <th scope="col" class="num">次日收益</th>
            <th scope="col">理由</th>
            <th scope="col">新闻来源</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in items" :key="`${item.code}-${item.rank}`">
            <td class="num ndp__rank">{{ item.rank }}</td>
            <td>
              <router-link class="ndp__stock" :to="`/stock/${item.code}`">
                <span class="ndp__name">{{ item.name || '--' }}</span>
                <span class="ndp__code">{{ item.code }}</span>
              </router-link>
            </td>
            <td class="num">
              <span
                class="ndp__score"
                :class="{ 'is-hot': item.score >= 80, 'is-warm': item.score >= 65 && item.score < 80 }"
              >
                {{ item.score }}
              </span>
            </td>
            <td class="num">
              <span class="ndp__prob">{{ item.probability.toFixed(1) }}%</span>
              <span class="ndp__prob-source">{{ probSourceText(item) }}</span>
            </td>
            <td class="num">
              <span class="ndp__return" :class="signedClass(item.nextOpenReturnPct)">
                {{ pct(item.nextOpenReturnPct) }}
              </span>
              <span class="ndp__status" :class="statusClass(item)">{{ statusText(item) }}</span>
              <span v-if="item.evalStatus === 'EVALUATED'" class="ndp__prob-source">
                收盘 {{ pct(item.nextReturnPct) }}
              </span>
            </td>
            <td class="ndp__reason">
              <span :title="item.reasons">{{ item.reasons || '--' }}</span>
              <span v-if="item.newsSummary" class="ndp__news-summary" :title="item.newsSummary">
                {{ item.newsSummary }}
              </span>
            </td>
            <td class="ndp__news">
              <template v-if="visibleNews(item).length">
                <a
                  v-for="news in visibleNews(item)"
                  :key="news.url"
                  class="ndp__link"
                  :href="news.url"
                  target="_blank"
                  rel="noopener noreferrer"
                  :title="news.title"
                >
                  {{ news.title || news.source || '原文' }}
                </a>
              </template>
              <span v-else class="ndp__meta">{{ item.newsSources.length ? '无原文链接' : '--' }}</span>
            </td>
          </tr>
        </tbody>
      </table>

      <div v-else-if="loading" class="ndp__state">加载中…</div>
      <div v-else-if="error" class="ndp__state ndp__state--error">{{ error }}</div>
      <div v-else class="ndp__state">
        <p class="ndp__empty-title">当天还没有预测榜单</p>
        <p class="ndp__empty-desc">
          预测在交易日 15:30 收盘后生成；首次上线需要先打开
          <code>iblog.predict.enabled</code>。没有历史样本时概率为启发式参考值。
        </p>
      </div>
    </div>

    <p class="ndp__foot">
      主指标为「T+1 开盘买入、收盘卖出」的可交易口径；开盘一字涨停视为买不进，不计命中。
      参考概率不是收益承诺，不构成投资建议。
    </p>
  </section>
</template>

<style scoped>
.table-wrap {
  overflow-x: auto;
}

.ndp__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  padding: var(--s-4) var(--s-5);
  border-bottom: 1px solid var(--c-border);
  flex-wrap: wrap;
}

.ndp__title {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  font-size: var(--t-md);
  font-weight: 620;
  white-space: nowrap;
}

.ndp__count {
  display: inline-grid;
  place-items: center;
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: var(--r-full);
  background: var(--c-surface-sunken);
  color: var(--c-text-muted);
  font-size: var(--t-xs);
  font-weight: 500;
}

.ndp__controls {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  flex-wrap: wrap;
}

.ndp__select {
  height: 30px;
  padding: 0 var(--s-2);
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface);
  color: var(--c-text);
  font-size: var(--t-sm);
}

.ndp__meta {
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.ndp__stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  margin: 0 var(--s-5) var(--s-3);
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-border);
  overflow: hidden;
}

.ndp__stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--s-3);
  background: var(--c-surface);
}

.ndp__stat-label {
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.ndp__stat-value {
  font-family: var(--f-mono, ui-monospace, monospace);
  font-size: var(--t-md);
  font-variant-numeric: tabular-nums;
}

.ndp__stat-sub {
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.is-up {
  color: var(--stock-up);
}

.is-down {
  color: var(--stock-down);
}

.ndp__buckets {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--s-2);
  padding: 0 var(--s-5) var(--s-3);
}

.ndp__bucket-label {
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.ndp__bucket-label + .ndp__bucket-label {
  margin-left: var(--s-2);
}

.ndp__chip {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  padding: 2px 8px;
  border: 1px solid var(--c-border);
  border-radius: var(--r-full);
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.ndp__chip b {
  font-family: var(--f-mono, ui-monospace, monospace);
  color: var(--c-text);
}

.ndp__table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--t-sm);
}

.ndp__table th,
.ndp__table td {
  padding: var(--s-2) var(--s-3);
  border-bottom: 1px solid var(--c-border);
  text-align: left;
  vertical-align: top;
}

.ndp__table th {
  font-size: var(--t-xs);
  font-weight: 500;
  color: var(--c-text-faint);
  white-space: nowrap;
}

.ndp__table .num {
  text-align: right;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.ndp__rank {
  color: var(--c-text-muted);
}

.ndp__stock {
  display: flex;
  flex-direction: column;
  gap: 1px;
  color: inherit;
  text-decoration: none;
}

.ndp__stock:hover .ndp__name {
  color: var(--c-accent);
}

.ndp__name {
  font-weight: 600;
}

.ndp__code {
  font-family: var(--f-mono, ui-monospace, monospace);
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.ndp__score {
  display: inline-grid;
  place-items: center;
  min-width: 30px;
  height: 22px;
  padding: 0 6px;
  border-radius: var(--r-full);
  background: var(--c-surface-sunken);
  font-family: var(--f-mono, ui-monospace, monospace);
  font-weight: 600;
}

.ndp__score.is-warm {
  color: var(--stock-up);
  background: color-mix(in srgb, var(--stock-up) 10%, transparent);
}

.ndp__score.is-hot {
  color: var(--c-surface);
  background: var(--stock-up);
}

.ndp__prob,
.ndp__return {
  display: block;
  font-family: var(--f-mono, ui-monospace, monospace);
  font-weight: 600;
}

.ndp__prob-source {
  display: block;
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.ndp__status {
  display: inline-block;
  margin-top: 2px;
  padding: 0 6px;
  border-radius: var(--r-full);
  font-size: var(--t-xs);
  background: var(--c-surface-sunken);
  color: var(--c-text-muted);
}

.ndp__status.is-hit {
  color: var(--stock-up);
  background: color-mix(in srgb, var(--stock-up) 12%, transparent);
}

.ndp__status.is-miss {
  color: var(--stock-down);
  background: color-mix(in srgb, var(--stock-down) 12%, transparent);
}

.ndp__status.is-pending,
.ndp__status.is-muted {
  color: var(--c-text-faint);
}

.ndp__reason {
  max-width: 280px;
  color: var(--c-text-secondary);
  line-height: 1.5;
}

.ndp__reason > span {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ndp__news-summary {
  margin-top: 2px;
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.ndp__news {
  max-width: 220px;
}

.ndp__link {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--t-xs);
  line-height: 1.6;
  color: var(--c-accent);
  text-decoration: none;
}

.ndp__link:hover {
  text-decoration: underline;
}

.ndp__state {
  padding: var(--s-8) var(--s-4);
  text-align: center;
  color: var(--c-text-muted);
  font-size: var(--t-sm);
}

.ndp__state--error {
  color: var(--c-danger, #d93025);
}

.ndp__empty-title {
  margin: 0;
  font-size: var(--t-md);
  font-weight: 600;
  color: var(--c-text-secondary);
}

.ndp__empty-desc {
  max-width: 52ch;
  margin: var(--s-2) auto 0;
  font-size: var(--t-sm);
  line-height: 1.6;
}

.ndp__empty-desc code {
  padding: 1px 4px;
  border-radius: 4px;
  background: var(--c-surface-sunken);
  font-size: 0.92em;
}

.ndp__foot {
  padding: var(--s-3) var(--s-5) var(--s-4);
  border-top: 1px solid var(--c-border);
  font-size: var(--t-xs);
  line-height: 1.6;
  color: var(--c-text-faint);
}

@media (max-width: 900px) {
  .ndp__stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .ndp__table {
    min-width: 760px;
  }
}
</style>
