<script setup lang="ts">
import { computed, ref } from 'vue'
import type { PaperNavPoint } from '@/api/paper'
import { fmtNum, toneClass } from '@/utils/stockFormat'

/**
 * 净值曲线：纯内联 SVG，不依赖任何图表库。
 *
 * <p>折线 + 半透明面积 + 4 条水平网格线，鼠标/手指悬停显示当日快照。
 * 颜色沿用 A 股口径：区间上涨红、下跌绿（与行情页一致）。</p>
 */
const props = defineProps<{
  items: PaperNavPoint[]
  loading?: boolean
}>()

const VIEW_W = 720
const VIEW_H = 250
const PAD = { top: 14, right: 16, bottom: 28, left: 62 }
const INNER_W = VIEW_W - PAD.left - PAD.right
const INNER_H = VIEW_H - PAD.top - PAD.bottom

/** 至少两个点才画线（一个点画不出趋势） */
const chartable = computed(() => !props.loading && props.items.length >= 2)

const bounds = computed(() => {
  const list = props.items
  if (!list.length) return { min: 0, max: 0 }
  let min = Number.POSITIVE_INFINITY
  let max = Number.NEGATIVE_INFINITY
  for (const point of list) {
    const value = point.totalAsset ?? 0
    if (value < min) min = value
    if (value > max) max = value
  }
  if (min === max) {
    // 一条水平线：上下各留 1%，别让折线贴边
    const pad = Math.max(Math.abs(min) * 0.01, 1)
    min -= pad
    max += pad
  }
  const margin = (max - min) * 0.12
  return { min: min - margin, max: max + margin }
})

const points = computed(() => {
  const list = props.items
  if (!list.length) return []
  const { min, max } = bounds.value
  const span = max - min || 1
  const stepX = list.length > 1 ? INNER_W / (list.length - 1) : 0
  return list.map((item, index) => ({
    x: PAD.left + index * stepX,
    y: PAD.top + (1 - ((item.totalAsset ?? 0) - min) / span) * INNER_H,
    item
  }))
})

const linePath = computed(() =>
  points.value.map((point, index) => `${index === 0 ? 'M' : 'L'}${point.x.toFixed(2)},${point.y.toFixed(2)}`).join(' ')
)

const areaPath = computed(() => {
  const list = points.value
  if (list.length < 2) return ''
  const baseY = (PAD.top + INNER_H).toFixed(2)
  const first = list[0]
  const last = list[list.length - 1]
  return `${linePath.value} L${last.x.toFixed(2)},${baseY} L${first.x.toFixed(2)},${baseY} Z`
})

/** 区间方向：末值 ≥ 首值算红 */
const trendUp = computed(() => {
  const list = props.items
  if (list.length < 2) return true
  return (list[list.length - 1].totalAsset ?? 0) >= (list[0].totalAsset ?? 0)
})

/** 区间收益率（%），基于总资产首尾差 */
const rangePct = computed(() => {
  const list = props.items
  if (list.length < 2) return null
  const first = list[0].totalAsset
  if (!first) return null
  return ((list[list.length - 1].totalAsset - first) / first) * 100
})

const yTicks = computed(() => {
  const { min, max } = bounds.value
  return [0, 1, 2, 3].map((index) => ({
    y: PAD.top + (INNER_H / 3) * index,
    value: max - ((max - min) / 3) * index
  }))
})

const xTicks = computed(() => {
  const list = props.items
  if (!list.length) return []
  const indexes = list.length === 1 ? [0] : [0, Math.floor((list.length - 1) / 2), list.length - 1]
  return [...new Set(indexes)].map((index) => ({
    x: points.value[index]?.x ?? 0,
    label: list[index].date.slice(5)
  }))
})

/** 坐标轴短标签：大了换万/亿，避免数字撑破左边距 */
function axisLabel(value: number): string {
  const abs = Math.abs(value)
  if (abs >= 1e8) return `${(value / 1e8).toFixed(2)}亿`
  if (abs >= 1e4) return `${(value / 1e4).toFixed(1)}万`
  return value.toFixed(0)
}

// —— 悬停 ——
const hoverIndex = ref<number | null>(null)
const plotRef = ref<HTMLDivElement | null>(null)

function onPointerMove(event: PointerEvent) {
  const list = points.value
  if (list.length < 2 || !plotRef.value) return
  const rect = plotRef.value.getBoundingClientRect()
  if (rect.width <= 0) return
  // 像素 → viewBox 坐标
  const x = ((event.clientX - rect.left) / rect.width) * VIEW_W
  const stepX = INNER_W / (list.length - 1)
  const raw = (x - PAD.left) / stepX
  const index = Math.round(Math.min(Math.max(raw, 0), list.length - 1))
  hoverIndex.value = Number.isFinite(index) ? index : null
}

function clearHover() {
  hoverIndex.value = null
}

const hoverPoint = computed(() => (hoverIndex.value === null ? null : points.value[hoverIndex.value] ?? null))

const tooltipStyle = computed(() => {
  const point = hoverPoint.value
  if (!point) return {}
  const leftPct = (point.x / VIEW_W) * 100
  const topPct = (point.y / VIEW_H) * 100
  return {
    left: `${Math.min(Math.max(leftPct, 14), 86)}%`,
    top: `${topPct}%`
  }
})

/** 点落在图表上方时，tooltip 翻到点下方，避免被 panel 的 overflow:hidden 裁掉 */
const tipBelow = computed(() => {
  const point = hoverPoint.value
  if (!point) return false
  return (point.y / VIEW_H) * 100 < 42
})

function signedPct(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '--'
  return `${value > 0 ? '+' : ''}${value.toFixed(2)}%`
}
</script>

<template>
  <div class="nav-chart">
    <div v-if="loading" class="nav-chart__skeleton">
      <span class="skeleton" />
    </div>

    <div v-else-if="!chartable" class="empty-state nav-chart__empty">
      <svg class="empty-state__icon" viewBox="0 0 24 24" aria-hidden="true">
        <path d="M3 17.5 8.5 12l3.5 3.5L21 6.5" stroke-linecap="round" stroke-linejoin="round" />
        <path d="M15.5 6.5H21v5.5" stroke-linecap="round" stroke-linejoin="round" />
        <path d="M3 21h18" stroke-linecap="round" opacity="0.45" />
      </svg>
      <p class="nav-chart__empty-title">
        {{ items.length ? '净值记录不足两条，暂不绘制曲线' : '还没有净值快照' }}
      </p>
      <p class="nav-chart__empty-desc">
        {{
          items.length
            ? '每日收盘结算后会追加一条净值快照，连续两个交易日后即可看到走势。'
            : '每个交易日收盘后会记录一条账户净值，攒够两条就能看到这条曲线。'
        }}
      </p>
    </div>

    <template v-else>
      <div class="nav-chart__head">
        <span class="nav-chart__legend">
          <i class="nav-chart__dot" :class="trendUp ? 'is-up' : 'is-down'" aria-hidden="true" />
          每日总资产净值
        </span>
        <span class="nav-chart__range" :class="trendUp ? 'tone-up' : 'tone-down'">
          区间 {{ signedPct(rangePct) }}
        </span>
      </div>

      <div ref="plotRef" class="nav-plot" :class="trendUp ? 'is-up' : 'is-down'">
        <svg
          class="nav-svg"
          :viewBox="`0 0 ${VIEW_W} ${VIEW_H}`"
          preserveAspectRatio="xMidYMid meet"
          role="img"
          :aria-label="`净值曲线，共 ${items.length} 个交易日，区间收益 ${signedPct(rangePct)}`"
          @pointermove="onPointerMove"
          @pointerleave="clearHover"
        >
          <!-- 水平网格 -->
          <g class="nav-grid">
            <line v-for="tick in yTicks" :key="`g-${tick.y}`" :x1="PAD.left" :x2="VIEW_W - PAD.right" :y1="tick.y" :y2="tick.y" />
          </g>

          <!-- 面积 + 折线 -->
          <path v-if="areaPath" class="nav-area" :d="areaPath" />
          <path class="nav-line" :d="linePath" />

          <!-- Y 轴刻度 -->
          <g class="nav-axis">
            <text
              v-for="tick in yTicks"
              :key="`y-${tick.y}`"
              :x="PAD.left - 10"
              :y="tick.y + 4"
              text-anchor="end"
            >
              {{ axisLabel(tick.value) }}
            </text>
            <text
              v-for="tick in xTicks"
              :key="`x-${tick.x}`"
              :x="tick.x"
              :y="VIEW_H - 8"
              text-anchor="middle"
            >
              {{ tick.label }}
            </text>
          </g>

          <!-- 悬停指示 -->
          <g v-if="hoverPoint" class="nav-hover">
            <line :x1="hoverPoint.x" :x2="hoverPoint.x" :y1="PAD.top" :y2="PAD.top + INNER_H" />
            <circle :cx="hoverPoint.x" :cy="hoverPoint.y" r="4.5" />
          </g>
        </svg>

        <div v-if="hoverPoint" class="nav-tip" :class="{ 'is-below': tipBelow }" :style="tooltipStyle" role="status">
          <span class="nav-tip__date">{{ hoverPoint.item.date }}</span>
          <span class="nav-tip__row">
            总资产
            <b>¥{{ fmtNum(hoverPoint.item.totalAsset) }}</b>
          </span>
          <span class="nav-tip__row">
            当日盈亏
            <b :class="toneClass(hoverPoint.item.dayPnl)">
              {{ hoverPoint.item.dayPnl == null ? '--' : `${hoverPoint.item.dayPnl >= 0 ? '+' : ''}${fmtNum(hoverPoint.item.dayPnl)}` }}
              <em>({{ signedPct(hoverPoint.item.dayPnlPct) }})</em>
            </b>
          </span>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.nav-chart {
  padding: var(--s-4) var(--s-5) var(--s-5);
}

.nav-chart__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  margin-bottom: var(--s-3);
  flex-wrap: wrap;
}

.nav-chart__legend {
  display: inline-flex;
  align-items: center;
  gap: var(--s-2);
  font-size: var(--t-sm);
  color: var(--c-text-muted);
}

.nav-chart__dot {
  width: 8px;
  height: 8px;
  border-radius: var(--r-full);
  background: var(--stock-up);
}

.nav-chart__dot.is-down {
  background: var(--stock-down);
}

.nav-chart__range {
  font-family: var(--f-mono, ui-monospace, monospace);
  font-size: var(--t-sm);
  font-variant-numeric: tabular-nums;
}

.nav-chart__skeleton {
  display: block;
  height: 220px;
}

.nav-chart__skeleton .skeleton {
  display: block;
  width: 100%;
  height: 100%;
  border-radius: var(--r-lg);
}

.nav-chart__empty {
  padding: var(--s-8) var(--s-4);
  text-align: center;
}

.nav-chart__empty-title {
  margin: var(--s-3) 0 0;
  font-size: var(--t-md);
  font-weight: 600;
  color: var(--c-text-secondary);
}

.nav-chart__empty-desc {
  max-width: 40ch;
  margin: var(--s-2) auto 0;
  font-size: var(--t-sm);
  line-height: 1.6;
  color: var(--c-text-muted);
}

.nav-plot {
  position: relative;
  touch-action: pan-y;
}

.nav-svg {
  display: block;
  width: 100%;
  height: auto;
  overflow: visible;
}

.nav-grid line {
  stroke: var(--c-border);
  stroke-width: 1;
  stroke-dasharray: 3 5;
}

.nav-line {
  fill: none;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.nav-plot.is-up .nav-line {
  stroke: var(--stock-up);
}

.nav-plot.is-down .nav-line {
  stroke: var(--stock-down);
}

.nav-area {
  stroke: none;
}

.nav-plot.is-up .nav-area {
  fill: color-mix(in srgb, var(--stock-up) 12%, transparent);
}

.nav-plot.is-down .nav-area {
  fill: color-mix(in srgb, var(--stock-down) 12%, transparent);
}

.nav-axis text {
  fill: var(--c-text-faint);
  font-family: var(--f-mono, ui-monospace, monospace);
  font-size: 11px;
}

.nav-hover line {
  stroke: var(--c-border-strong);
  stroke-width: 1;
  stroke-dasharray: 3 3;
}

.nav-hover circle {
  stroke: var(--c-surface);
  stroke-width: 2;
}

.nav-plot.is-up .nav-hover circle {
  fill: var(--stock-up);
}

.nav-plot.is-down .nav-hover circle {
  fill: var(--stock-down);
}

.nav-tip {
  position: absolute;
  z-index: 5;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 132px;
  padding: var(--s-2) var(--s-3);
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface);
  box-shadow: var(--e-lg);
  font-size: var(--t-xs);
  color: var(--c-text-secondary);
  pointer-events: none;
  transform: translate(-50%, -115%);
  white-space: nowrap;
}

.nav-tip.is-below {
  transform: translate(-50%, 18%);
}

.nav-tip__date {
  font-family: var(--f-mono, ui-monospace, monospace);
  color: var(--c-text-faint);
}

.nav-tip__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-3);
}

.nav-tip__row b {
  font-family: var(--f-mono, ui-monospace, monospace);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--c-text);
}

.nav-tip__row em {
  font-style: normal;
  font-size: 0.92em;
}

@media (max-width: 600px) {
  .nav-chart {
    padding: var(--s-3) var(--s-4) var(--s-4);
  }

  .nav-tip {
    min-width: 116px;
  }
}
</style>
