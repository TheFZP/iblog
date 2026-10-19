<script setup lang="ts">
import { computed } from 'vue'
import type { IndustryData } from '@/api/stock'
import { toneClass } from '@/utils/stockFormat'

/**
 * 行业分析弹窗。
 *
 * <p>对应桌面版 {@code IndustryModal.vue}。两块内容：</p>
 * <ul>
 *   <li><b>市场表现</b>：个股 vs 沪深300 在近1月/3月/6月/年内的涨幅对比。
 *       单看个股涨跌没意义，要看它跑赢还是跑输大盘。</li>
 *   <li><b>营收排名</b>：同行业公司的营收/净利/市值对比，一眼看出龙头是谁。</li>
 * </ul>
 */

const props = defineProps<{
  show: boolean
  data: IndustryData | null
  loading?: boolean
  error?: string
  /** 当前个股代码，用于高亮表格里「自己」那一行 */
  code?: string
  /** 当前价涨跌幅，作为「今日」一格 */
  changePct?: number
}>()

const emit = defineEmits<{ close: []; retry: [] }>()

/** TIME_TYPE → 中文标签。1=近1月 2=近3月 3=近6月 4=今年以来 */
const PERIOD_LABELS: Record<number, string> = {
  1: '近 1 月',
  2: '近 3 月',
  3: '近 6 月',
  4: '今年以来'
}

const performance = computed(() => props.data?.marketPerformance ?? [])
const ranking = computed(() => props.data?.revenueRanking ?? [])

/** 是否港股（没有行业分析数据） */
const isHkOnly = computed(
  () => !props.loading && !props.error && !!props.data && ranking.value.length === 0 && performance.value.length === 0
)

function fmtYi(v: number | undefined | null): string {
  if (v == null || !Number.isFinite(v)) return '—'
  const yi = v / 1e8
  if (Math.abs(yi) >= 10000) return (yi / 10000).toFixed(2) + ' 万亿'
  return yi.toFixed(2) + ' 亿'
}

function fmtPct(v: number | undefined | null): string {
  if (v == null || !Number.isFinite(v)) return '—'
  return (v >= 0 ? '+' : '') + v.toFixed(2) + '%'
}

/** 个股相对沪深300 的超额收益，正数=跑赢 */
function excess(perf: { changeRate: number; hs300ChangeRate: number }): number {
  return perf.changeRate - perf.hs300ChangeRate
}

/** 前 3 名给「龙头/龙二/龙三」徽章 —— 顺序即营收排名，不是数组下标 */
function rankBadge(item: { totalOperateIncomeRank: number }): string {
  switch (item.totalOperateIncomeRank) {
    case 1:
      return '龙头'
    case 2:
      return '龙二'
    case 3:
      return '龙三'
    default:
      return ''
  }
}
</script>

<template>
  <div v-if="show" class="stock-modal" @click.self="emit('close')">
    <div class="stock-modal__box" role="dialog" aria-modal="true" aria-label="行业分析">
      <header class="stock-modal__head">
        <h2 class="stock-modal__title">
          {{ data?.industryName || '行业分析' }}
          <span v-if="data?.industryName && data.industryName !== '港股'" class="industry-tag">行业</span>
        </h2>
        <button class="stock-modal__close" aria-label="关闭" @click="emit('close')">✕</button>
      </header>

      <div v-if="loading" class="stock-modal__state">
        <span class="spinner" aria-hidden="true"></span>
        <span>正在加载行业数据…</span>
      </div>

      <div
        v-else-if="error"
        class="stock-modal__state stock-modal__state--error"
        role="button"
        tabindex="0"
        @click="emit('retry')"
        @keydown.enter.self="emit('retry')"
      >
        <span>加载失败，点击重试</span>
        <small>{{ error }}</small>
      </div>

      <div v-else-if="isHkOnly" class="stock-modal__state">
        港股暂无行业分析数据（东方财富 F10 只覆盖 A 股）。
      </div>

      <template v-else-if="data">
        <!-- ===== 市场表现 ===== -->
        <section v-if="performance.length || changePct != null" class="sect">
          <h3 class="sect__title">市场表现</h3>
          <p class="sect__hint">个股涨跌 vs 沪深300 同期，看的是跑赢还是跑输。</p>
          <div class="perf-grid">
            <div class="perf-card">
              <span class="perf-card__label">今日</span>
              <span class="perf-card__main" :class="toneClass(changePct)">{{ fmtPct(changePct) }}</span>
            </div>
            <div v-for="perf in performance" :key="perf.timeType" class="perf-card">
              <span class="perf-card__label">{{ PERIOD_LABELS[perf.timeType] || '—' }}</span>
              <span class="perf-card__main" :class="toneClass(perf.changeRate)">{{ fmtPct(perf.changeRate) }}</span>
              <span class="perf-card__vs">
                沪深300
                <em :class="toneClass(perf.hs300ChangeRate)">{{ fmtPct(perf.hs300ChangeRate) }}</em>
              </span>
              <span class="perf-card__excess" :class="toneClass(excess(perf))">
                超额 {{ fmtPct(excess(perf)) }}
              </span>
            </div>
          </div>
        </section>

        <!-- ===== 营收排名 ===== -->
        <section v-if="ranking.length" class="sect">
          <h3 class="sect__title">
            行业对比
            <small v-if="data.reportType" class="sect__badge">{{ data.reportType }}</small>
          </h3>
          <div class="rank-table">
            <div class="rank-row rank-row--head">
              <span class="rank-cell rank-cell--name">公司</span>
              <span class="rank-cell rank-cell--num">营收</span>
              <span class="rank-cell rank-cell--num">净利润</span>
              <span class="rank-cell rank-cell--num">总市值</span>
            </div>
            <div
              v-for="item in ranking"
              :key="item.stockCode"
              class="rank-row"
              :class="{ 'is-self': item.stockCode === code }"
            >
              <span class="rank-cell rank-cell--name">
                <span
                  v-if="rankBadge(item)"
                  class="rank-badge"
                  :class="'rank-badge--' + item.totalOperateIncomeRank"
                  >{{ rankBadge(item) }}</span
                >
                <span class="rank-name">{{ item.stockName }}</span>
                <span class="rank-code">{{ item.stockCode }}</span>
              </span>
              <span class="rank-cell rank-cell--num">{{ fmtYi(item.totalOperateIncome) }}</span>
              <span
                class="rank-cell rank-cell--num"
                :class="item.netProfit < 0 ? 'is-loss' : ''"
                >{{ fmtYi(item.netProfit) }}</span
              >
              <span class="rank-cell rank-cell--num">{{ fmtYi(item.totalCap) }}</span>
            </div>
          </div>

          <p v-if="data.industryAverage" class="sect__foot">
            行业平均：营收 {{ fmtYi(data.industryAverage.totalOperateIncome) }} · 净利润
            {{ fmtYi(data.industryAverage.netProfit) }} · 总市值 {{ fmtYi(data.industryAverage.totalCap) }}
          </p>
        </section>
      </template>
    </div>
  </div>
</template>

<style scoped>
.industry-tag {
  margin-left: 8px;
  padding: 1px 7px;
  border-radius: 999px;
  background: var(--c-brand-soft);
  color: var(--c-brand);
  font-size: var(--t-xs);
  font-weight: 500;
  vertical-align: middle;
}

.sect + .sect {
  margin-top: var(--s-6);
  padding-top: var(--s-5);
  border-top: 1px solid var(--c-border);
}

.sect__title {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  margin: 0 0 var(--s-1);
  font-size: var(--t-base);
  font-weight: 600;
}

.sect__badge {
  font-size: var(--t-xs);
  font-weight: 400;
  color: var(--c-text-faint);
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--c-surface-sunken);
}

.sect__hint {
  margin: 0 0 var(--s-3);
  font-size: var(--t-sm);
  color: var(--c-text-muted);
}

.sect__foot {
  margin: var(--s-3) 0 0;
  font-size: var(--t-sm);
  color: var(--c-text-muted);
}

/* ---------- 市场表现 ---------- */
.perf-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(118px, 1fr));
  gap: var(--s-2);
}

.perf-card {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--s-3);
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface-sunken);
}

.perf-card__label {
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.perf-card__main {
  font-size: var(--t-xl);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
}

.perf-card__vs {
  font-size: var(--t-xs);
  color: var(--c-text-faint);
  display: flex;
  gap: 4px;
  align-items: baseline;
}

.perf-card__vs em {
  font-style: normal;
  font-variant-numeric: tabular-nums;
}

.perf-card__excess {
  margin-top: 2px;
  font-size: var(--t-xs);
  font-variant-numeric: tabular-nums;
}

/* ---------- 营收排名表 ---------- */
.rank-table {
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  /* 不用 overflow:hidden 圆裁 —— 那样会把 is-self 的左侧色条一起裁掉。
     改为让首末行自己带圆角。 */
  overflow: visible;
}

.rank-row:first-child {
  border-radius: var(--r) var(--r) 0 0;
}

.rank-row:last-child {
  border-radius: 0 0 var(--r) var(--r);
}

.rank-row {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) repeat(3, minmax(0, 1fr));
  gap: var(--s-2);
  align-items: center;
  padding: 9px var(--s-3);
  font-size: var(--t-sm);
  border-top: 1px solid var(--c-border);
}

.rank-row:first-child {
  border-top: 0;
}

.rank-row--head {
  background: var(--c-surface-sunken);
  font-size: var(--t-xs);
  color: var(--c-text-muted);
  font-weight: 500;
}

/* 自己那一行加左侧色条，而不是整行染色 —— 整行会盖住涨跌色 */
.rank-row.is-self {
  background: color-mix(in srgb, var(--c-brand) 6%, transparent);
  box-shadow: inset 3px 0 0 var(--c-brand);
}

.rank-cell--name {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.rank-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rank-code {
  flex: none;
  font-size: var(--t-xs);
  color: var(--c-text-faint);
  font-variant-numeric: tabular-nums;
}

.rank-cell--num {
  text-align: right;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.rank-cell--num.is-loss {
  color: var(--stock-down);
}

.rank-badge {
  flex: none;
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 10px;
  font-weight: 600;
  line-height: 1.5;
  background: color-mix(in srgb, var(--c-accent) 14%, transparent);
  color: var(--c-accent);
}

.rank-badge--1 {
  background: color-mix(in srgb, #b45309 18%, transparent);
  color: #b45309;
}

:root[data-theme='dark'] .rank-badge--1 {
  background: color-mix(in srgb, #f0a05a 20%, transparent);
  color: #f0a05a;
}

/* ---------- 加载指示 ---------- */
.spinner {
  width: 18px;
  height: 18px;
  border: 2px solid var(--c-border-strong);
  border-top-color: var(--c-brand);
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(1turn);
  }
}

@media (prefers-reduced-motion: reduce) {
  .spinner {
    animation-duration: 2.4s;
  }
}

@media (max-width: 640px) {
  .rank-row {
    grid-template-columns: minmax(0, 1.5fr) repeat(2, minmax(0, 1fr));
  }

  /* 市值这一列在窄屏最不重要，直接隐藏，避免每列挤到 3 个字 */
  .rank-row > .rank-cell--num:last-child {
    display: none;
  }
}
</style>
