<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  ColorType,
  HistogramSeries,
  LineSeries,
  createChart,
  type BusinessDay,
  type IChartApi,
  type ISeriesApi
} from 'lightweight-charts'
import type { MoneyFlowHistoryItem } from '@/api/stock'
import { UP_COLOR, DOWN_COLOR, chartTheme, isDarkMode } from '@/utils/stockFormat'

/**
 * 近 N 日主力净流入柱状图。
 *
 * <p>与桌面版 `MoneyFlowChart.vue` 同源思路：柱状图画每日主力净流入（红入绿出），
 * 叠加 5 日均线看趋势，上方给今日/5日/10日/20日累计。改用本项目的 lightweight-charts
 * v5 API 与 `chartTheme` 令牌，深浅色与 K 线图保持一致。</p>
 */

const props = withDefaults(
  defineProps<{
    /** 按日期升序（旧→新） */
    data: MoneyFlowHistoryItem[]
    loading?: boolean
  }>(),
  { loading: false }
)

const container = ref<HTMLDivElement | null>(null)

let chart: IChartApi | null = null
let histSeries: ISeriesApi<'Histogram'> | null = null
let maSeries: ISeriesApi<'Line'> | null = null
let resizeObserver: ResizeObserver | null = null
/** 已 fitContent 的数据长度，避免定时刷新时把用户缩放重置掉 */
let fittedLen = -1

const MA_DAYS = 5

/**
 * 万元 → 展示文本，**保留正负号**。
 *
 * <p>没有复用 stockFormat 的 fmtTurnover：那个函数用 `wan >= 1e4` 判断是否换算成「亿」，
 * 负值永远进不了亿分支，-12 亿会显示成 "-123456万"，读起来完全是废的。</p>
 */
function fmtWan(v: number | null | undefined): string {
  if (v == null || Number.isNaN(v)) {
    return '--'
  }
  const abs = Math.abs(v)
  const sign = v < 0 ? '-' : ''
  if (abs >= 1e4) {
    return `${sign}${(abs / 1e4).toFixed(2)}亿`
  }
  return `${sign}${abs.toFixed(0)}万`
}

function fmtSignedWan(v: number): string {
  return v > 0 ? `+${fmtWan(v)}` : fmtWan(v)
}

/** 今日 + 近 5/10/20 日累计净流入 */
function sumOf(n: number): number {
  const d = props.data
  return d.slice(Math.max(0, d.length - n)).reduce((a, b) => a + b.mainNetInflow, 0)
}

const isEmpty = () => props.data.length === 0

/** 色板：净流入红、净流出绿，深浅色各一套 */
function colors() {
  const dark = isDarkMode()
  return {
    up: dark ? UP_COLOR.dark : UP_COLOR.light,
    down: dark ? DOWN_COLOR.dark : DOWN_COLOR.light,
    theme: chartTheme(dark)
  }
}

function initChart() {
  if (!container.value || chart) {
    return
  }
  const { theme } = colors()

  chart = createChart(container.value, {
    width: container.value.clientWidth,
    height: container.value.clientHeight,
    layout: {
      background: { type: ColorType.Solid, color: 'transparent' },
      textColor: theme.text,
      fontSize: 11,
      attributionLogo: false
    },
    grid: {
      vertLines: { color: theme.grid },
      horzLines: { color: theme.grid }
    },
    rightPriceScale: {
      borderColor: theme.border,
      scaleMargins: { top: 0.12, bottom: 0.08 }
    },
    timeScale: {
      borderColor: theme.border,
      timeVisible: false,
      fixLeftEdge: true,
      fixRightEdge: true
    },
    // 零轴：净流入 / 净流出的分界，所有柱子的基准
    crosshair: {
      vertLine: { color: theme.refLine, width: 1, style: 2, labelBackgroundColor: theme.refLine },
      horzLine: { color: theme.refLine, width: 1, style: 2, labelBackgroundColor: theme.refLine }
    },
    localization: {
      priceFormatter: (p: number) => fmtWan(p)
    },
    handleScroll: { vertTouchDrag: false }
  })

  histSeries = chart.addSeries(HistogramSeries, {
    priceFormat: { type: 'custom', formatter: (p: number) => fmtWan(p) },
    priceLineVisible: false,
    lastValueVisible: false,
    base: 0
  })

  maSeries = chart.addSeries(LineSeries, {
    color: theme.avgLine,
    lineWidth: 1,
    priceLineVisible: false,
    lastValueVisible: false,
    crosshairMarkerVisible: false,
    priceFormat: { type: 'custom', formatter: (p: number) => fmtWan(p) }
  })

  resizeObserver = new ResizeObserver(() => {
    if (chart && container.value) {
      chart.applyOptions({ width: container.value.clientWidth, height: container.value.clientHeight })
    }
  })
  resizeObserver.observe(container.value)

  applyData()
}

function applyData() {
  if (!chart || !histSeries || !maSeries) {
    return
  }
  if (props.data.length === 0) {
    histSeries.setData([])
    maSeries.setData([])
    fittedLen = -1
    return
  }

  const { up, down } = colors()
  // 防御性排序：lightweight-charts 要求时间严格升序，后端顺序若有变化也不该崩
  const sorted = [...props.data].sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : 0))

  histSeries.setData(
    sorted.map((it) => ({
      time: it.date as BusinessDay | string,
      value: it.mainNetInflow,
      color: it.mainNetInflow >= 0 ? up : down
    })) as never
  )

  // 5 日均线：前 4 天没有均值，跳过
  const ma: { time: string; value: number }[] = []
  for (let i = MA_DAYS - 1; i < sorted.length; i++) {
    let s = 0
    for (let j = i - MA_DAYS + 1; j <= i; j++) {
      s += sorted[j].mainNetInflow
    }
    ma.push({ time: sorted[i].date, value: s / MA_DAYS })
  }
  maSeries.setData(ma as never)

  // 只在数据长度变化时才 fitContent，定时刷新保留用户当前的缩放
  if (sorted.length !== fittedLen) {
    chart.timeScale().fitContent()
    fittedLen = sorted.length
  }
}

/** 深浅色切换：canvas 拿不到 CSS 变量，必须主动更新选项与颜色 */
function applyTheme() {
  if (!chart || !histSeries || !maSeries) {
    return
  }
  const { theme } = colors()
  chart.applyOptions({
    layout: { textColor: theme.text },
    grid: { vertLines: { color: theme.grid }, horzLines: { color: theme.grid } },
    rightPriceScale: { borderColor: theme.border },
    timeScale: { borderColor: theme.border }
  })
  maSeries.applyOptions({ color: theme.avgLine })
  applyData()
}

watch(
  () => props.data,
  () => nextTick(applyData)
)

onMounted(() => {
  nextTick(initChart)
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  if (chart) {
    chart.remove()
    chart = null
    histSeries = null
    maSeries = null
  }
})

defineExpose({ applyTheme })
</script>

<template>
  <div class="mf">
    <div class="mf__head">
      <span class="mf__title">主力净流入 · 近 {{ data.length || 30 }} 日</span>
      <template v-if="!isEmpty()">
        <span class="mf__chips">
          <span class="mf__chip" :class="sumOf(1) >= 0 ? 'tone-up' : 'tone-down'">
            今日 {{ fmtSignedWan(sumOf(1)) }}
          </span>
          <span class="mf__chip" :class="sumOf(5) >= 0 ? 'tone-up' : 'tone-down'">
            5日 {{ fmtSignedWan(sumOf(5)) }}
          </span>
          <span class="mf__chip" :class="sumOf(10) >= 0 ? 'tone-up' : 'tone-down'">
            10日 {{ fmtSignedWan(sumOf(10)) }}
          </span>
          <span class="mf__chip" :class="sumOf(20) >= 0 ? 'tone-up' : 'tone-down'">
            20日 {{ fmtSignedWan(sumOf(20)) }}
          </span>
        </span>
        <span class="mf__legend" aria-hidden="true">
          <i class="mf__dot mf__dot--up" />净流入
          <i class="mf__dot mf__dot--down" />净流出
          <i class="mf__dot mf__dot--ma" />{{ MA_DAYS }}日均线
        </span>
      </template>
    </div>

    <div ref="container" class="mf__chart" role="img" :aria-label="`近 ${data.length} 日主力净流入柱状图`" />

    <div v-if="isEmpty() && !loading" class="mf__empty">暂无资金流向历史数据</div>
    <div v-else-if="isEmpty() && loading" class="mf__empty">加载中…</div>
  </div>
</template>

<style scoped>
.mf {
  display: flex;
  flex-direction: column;
  gap: var(--s-2);
  /* 图例小圆点用：浅色/深色各一套，跟随 stockFormat 的涨跌色 */
  --mf-up: #d93025;
  --mf-down: #14803c;
  --mf-ma: #d99b1e;
}

:root[data-theme='dark'] .mf {
  --mf-up: #ff6b5e;
  --mf-down: #45c26f;
  --mf-ma: #e8b339;
}

.mf__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--s-2) var(--s-3);
}

.mf__title {
  font-size: var(--t-sm);
  font-weight: 600;
  color: var(--c-text-secondary);
}

.mf__chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-1);
}

.mf__chip {
  font-size: var(--t-xs);
  font-variant-numeric: tabular-nums;
  padding: 1px 6px;
  border-radius: var(--r-sm);
  background: var(--c-surface-sunken);
}

.mf__legend {
  display: inline-flex;
  align-items: center;
  gap: var(--s-1);
  margin-left: auto;
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.mf__dot {
  width: 8px;
  height: 8px;
  border-radius: 2px;
  display: inline-block;
  margin-left: var(--s-2);
}

.mf__dot--up {
  background: var(--mf-up);
}

.mf__dot--down {
  background: var(--mf-down);
}

.mf__dot--ma {
  background: var(--mf-ma);
}

.mf__chart {
  width: 100%;
  height: 180px;
}

.mf__empty {
  font-size: var(--t-sm);
  color: var(--c-text-muted);
  padding: var(--s-6) 0;
  text-align: center;
}

@media (max-width: 720px) {
  .mf__legend {
    display: none;
  }

  .mf__chart {
    height: 150px;
  }
}
</style>
