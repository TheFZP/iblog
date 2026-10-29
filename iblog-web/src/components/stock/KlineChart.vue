<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  createChart,
  ColorType,
  CandlestickSeries,
  HistogramSeries,
  LineSeries,
  CrosshairMode,
  type IChartApi,
  type ISeriesApi,
  type IPriceLine,
  type UTCTimestamp,
  type BusinessDay
} from 'lightweight-charts'
import type { KlineItem } from '@/api/stock'
import { latestRightRange } from '@/utils/chartViewport'
import { MA_COLOR_MAP } from '@/composables/useChartSettings'
import {
  MINUTE_PERIODS,
  chartTheme,
  isDarkMode,
  toChartTime,
  UP_COLOR,
  DOWN_COLOR
} from '@/utils/stockFormat'

/**
 * K 线图。
 *
 * <p>选 lightweight-charts 而不是 ECharts：桌面版用的就是它，canvas 渲染、
 * K 线/成交量/均线/价格线都是原生能力，体积也比 ECharts 小一个量级。</p>
 *
 * <p><b>v5 的 API 与 v4 不同</b>：系列用 {@code chart.addSeries(CandlestickSeries, opts)}
 * 创建，而不是 {@code addCandlestickSeries(opts)}，旧写法在 v5 里会直接报错。</p>
 */

const props = withDefaults(
  defineProps<{
    data: KlineItem[]
    period: string
    loading?: boolean
    /** 持仓成本价，大于 0 时画一条成本线 */
    costPrice?: number | null
    /** 昨收，用于画参考线 */
    prevClose?: number | null
    /**
     * 要显示的均线周期。
     *
     * <p>由调用方传入而不是组件内部读 localStorage：技术分析页与详情页可以各自
     * 传不同组合（比如分析页固定显示 MA20/MA60 便于判断趋势），组件保持无状态，
     * 单测和 Storybook 式的隔离渲染才好做。</p>
     */
    maPeriods?: number[]
    /** 是否画近 30 根 K 线的最高/最低价参考线 */
    show30DayHL?: boolean
  }>(),
  {
    loading: false,
    costPrice: null,
    prevClose: null,
    maPeriods: () => [5, 10, 20, 60],
    show30DayHL: false
  }
)

const container = ref<HTMLDivElement | null>(null)
/** 均线最新值，显示在图上方 */
const maValues = ref<Record<number, number>>({})

let chart: IChartApi | null = null
let candleSeries: ISeriesApi<'Candlestick'> | null = null
let volumeSeries: ISeriesApi<'Histogram'> | null = null
const maSeries = new Map<number, ISeriesApi<'Line'>>()
/** 已创建过 series 的周期，增删均线时按这个差量建/删 series */
let builtPeriods: number[] = []
let costLine: IPriceLine | null = null
let prevCloseLine: IPriceLine | null = null
/** 30 日最高/最低参考线 */
let high30Line: IPriceLine | null = null
let low30Line: IPriceLine | null = null
let resizeObserver: ResizeObserver | null = null
/** 已 fitContent 的数据身份，切换股票/周期才重置视图，定时刷新时保留缩放 */
let fittedKey = ''
/** 当前数据根数，滚轮缩放后要把右边缘对齐到最后一根 */
let dataLength = 0
/** 挂了 wheel 监听的容器，卸载时要摘掉 */
let wheelTarget: HTMLDivElement | null = null
/** 兜底对齐用的帧号（chart 内部若异步处理滚轮，下一帧再校正一次） */
let lockFrame = 0

const MA_COLORS = MA_COLOR_MAP

function createOrUpdateChart() {
  if (!container.value) {
    return
  }
  const dark = isDarkMode()
  const theme = chartTheme(dark)

  if (!chart) {
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
      crosshair: {
        mode: CrosshairMode.Normal,
        vertLine: { color: theme.text, width: 1, style: 2, labelBackgroundColor: theme.text },
        horzLine: { color: theme.text, width: 1, style: 2, labelBackgroundColor: theme.text }
      },
      rightPriceScale: {
        borderColor: theme.border,
        // 底部留四分之一给成交量
        scaleMargins: { top: 0.08, bottom: 0.26 }
      },
      timeScale: {
        borderColor: theme.border,
        timeVisible: false,
        ticksVisible: true,
        fixLeftEdge: true,
        fixRightEdge: true
      },
      handleScroll: { vertTouchDrag: false }
    })

    candleSeries = chart.addSeries(CandlestickSeries, {
      upColor: dark ? UP_COLOR.dark : UP_COLOR.light,
      downColor: dark ? DOWN_COLOR.dark : DOWN_COLOR.light,
      borderUpColor: dark ? UP_COLOR.dark : UP_COLOR.light,
      borderDownColor: dark ? DOWN_COLOR.dark : DOWN_COLOR.light,
      wickUpColor: dark ? UP_COLOR.dark : UP_COLOR.light,
      wickDownColor: dark ? DOWN_COLOR.dark : DOWN_COLOR.light,
      priceFormat: { type: 'price', precision: 2, minMove: 0.01 }
    })

    volumeSeries = chart.addSeries(HistogramSeries, {
      priceFormat: { type: 'volume' },
      priceScaleId: 'volume',
      priceLineVisible: false
    })
    chart.priceScale('volume').applyOptions({ scaleMargins: { top: 0.8, bottom: 0 } })

    resizeObserver = new ResizeObserver(() => {
      if (container.value && chart) {
        chart.applyOptions({
          width: container.value.clientWidth,
          height: container.value.clientHeight
        })
      }
    })
    resizeObserver.observe(container.value)

    // 滚轮缩放后把最新一根 K 线锁在右边缘（见 lockLatestToRight）
    wheelTarget = container.value
    wheelTarget.addEventListener('wheel', handleWheel, { passive: true })
  }
}

/**
 * 按当前需要的周期增删均线 series。
 *
 * <p>为什么不一次性把 5/10/20/30/60/120/250 全建好、只控制显隐：lightweight-charts 的
 * series 即使没数据也会占一条 legend 与一次重绘开销，250 日均线在只有 120 根数据时
 * 完全空转。按差量建删既省这层开销，也让图例天然只出现用户勾选的周期。</p>
 *
 * <p>已存在的周期不重建——重建会丢掉 series 缓冲，切勾选时图会闪一下。</p>
 */
function syncMaSeries() {
  if (!chart) {
    return
  }
  const wanted = props.maPeriods.length > 0 ? props.maPeriods : []
  for (const p of builtPeriods) {
    if (!wanted.includes(p)) {
      const series = maSeries.get(p)
      if (series) {
        chart.removeSeries(series)
        maSeries.delete(p)
      }
    }
  }
  for (const p of wanted) {
    if (maSeries.has(p)) {
      continue
    }
    maSeries.set(
      p,
      chart.addSeries(LineSeries, {
        color: MA_COLORS[p] ?? '#8a8f98',
        lineWidth: 1,
        priceLineVisible: false,
        lastValueVisible: false,
        crosshairMarkerVisible: false
      })
    )
  }
  builtPeriods = [...wanted]
}

/**
 * 滚轮缩放时的右边缘锁定。
 *
 * <p>lightweight-charts 的滚轮缩放以<b>鼠标位置</b>为锚点：鼠标偏左时放大，
 * 最新几根会被挤出右侧视口；缩小时同样会偏离。这里在每次滚轮后把可见逻辑
 * 范围的右端重新对齐到最后一根，让缩放围绕「最新时间」进行。</p>
 *
 * <p>拖动平移（非滚轮）不触发锁定，否则就没法翻看历史了。</p>
 */
function lockLatestToRight() {
  if (!chart) {
    return
  }
  const timeScale = chart.timeScale()
  const target = latestRightRange(dataLength, timeScale.getVisibleLogicalRange())
  if (target === null) {
    return
  }
  if (target === 'fit') {
    timeScale.fitContent()
    return
  }
  timeScale.setVisibleLogicalRange(target)
}

/** 同步对齐一次，再在下一帧兜一次（chart 内部若异步处理滚轮时也能接住） */
function handleWheel() {
  lockLatestToRight()
  if (lockFrame) {
    cancelAnimationFrame(lockFrame)
  }
  lockFrame = requestAnimationFrame(() => {
    lockFrame = 0
    lockLatestToRight()
  })
}

/** 均线：简单移动平均，不足周期的点不输出 */
function computeMA(
  data: { time: UTCTimestamp | BusinessDay; close: number }[],
  period: number
): { time: UTCTimestamp | BusinessDay; value: number }[] {
  const out: { time: UTCTimestamp | BusinessDay; value: number }[] = []
  let sum = 0
  for (let i = 0; i < data.length; i++) {
    sum += data[i].close
    if (i >= period) {
      sum -= data[i - period].close
    }
    if (i >= period - 1) {
      out.push({ time: data[i].time, value: sum / period })
    }
  }
  return out
}

function render() {
  if (!chart) {
    return
  }
  // 先按当前勾选补齐/移除均线 series，下面的 setData 才有对象可写
  syncMaSeries()
  const dark = isDarkMode()
  const up = dark ? UP_COLOR.dark : UP_COLOR.light
  const down = dark ? DOWN_COLOR.dark : DOWN_COLOR.light
  const theme = chartTheme(dark)

  const isMinute = MINUTE_PERIODS.has(props.period)
  chart.applyOptions({ timeScale: { timeVisible: isMinute, secondsVisible: false } })

  if (!props.data || props.data.length === 0) {
    candleSeries?.setData([])
    volumeSeries?.setData([])
    maSeries.forEach((s) => s.setData([]))
    maValues.value = {}
    dataLength = 0
    return
  }

  const sorted = [...props.data].sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : 0))
  const candles = sorted.map((k) => ({
    time: toChartTime(k.date) as UTCTimestamp | BusinessDay,
    open: k.open,
    high: k.high,
    low: k.low,
    close: k.close
  }))
  const volumes = sorted.map((k) => ({
    time: toChartTime(k.date) as UTCTimestamp | BusinessDay,
    value: k.volume,
    color: k.close >= k.open ? theme.upVolume : theme.downVolume
  }))
  dataLength = candles.length

  // 图例颜色要跟着涨跌，避免深色模式下与柱体不协调
  candleSeries?.applyOptions({
    upColor: up,
    downColor: down,
    borderUpColor: up,
    borderDownColor: down,
    wickUpColor: up,
    wickDownColor: down
  })

  candleSeries?.setData(candles)
  volumeSeries?.setData(volumes)

  const latest: Record<number, number> = {}
  const closesAndTimes = sorted.map((k, i) => ({ time: candles[i].time, close: k.close }))
  for (const p of props.maPeriods) {
    const series = maSeries.get(p)
    if (!series) continue
    const ma = computeMA(closesAndTimes, p)
    series.setData(ma)
    if (ma.length > 0) {
      latest[p] = ma[ma.length - 1].value
    }
  }
  maValues.value = latest

  renderPriceLines(sorted)

  // 只在数据身份变化（切股票/周期）时重置视图，定时刷新不打断用户的缩放
  const key = `${sorted[0].date}:${sorted.length}:${props.period}`
  if (key !== fittedKey) {
    fittedKey = key
    chart.timeScale().fitContent()
  }
}

/**
 * 画参考线（成本 / 昨收 / 近 30 日高低）。
 *
 * <p>30 日高低线取的是<b>当前画布上最近 30 根</b>的最高最低价：日内周期看的是近 30 根
 * 分钟线的箱体，日线看的是近 30 个交易日的箱体，两种口径都由同一条规则自然得出。
 * 这与「固定 30 个交易日的日线高低」不同，但用户看图时盯的本来就是这个可视窗口，
 * 所以按数据取更符合直觉（标题里写清了是最近 30 根）。</p>
 */
function renderPriceLines(bars?: KlineItem[]) {
  if (!candleSeries) {
    return
  }
  // 成本线
  if (props.costPrice != null && props.costPrice > 0) {
    if (costLine) {
      costLine.applyOptions({ price: props.costPrice })
    } else {
      costLine = candleSeries.createPriceLine({
        price: props.costPrice,
        color: '#f0b429',
        lineWidth: 1,
        lineStyle: 2,
        axisLabelVisible: true,
        title: '成本'
      })
    }
  } else if (costLine) {
    candleSeries.removePriceLine(costLine)
    costLine = null
  }

  // 昨收参考线
  if (props.prevClose != null && props.prevClose > 0) {
    const theme = chartTheme(isDarkMode())
    if (prevCloseLine) {
      prevCloseLine.applyOptions({ price: props.prevClose })
    } else {
      prevCloseLine = candleSeries.createPriceLine({
        price: props.prevClose,
        color: theme.refLine,
        lineWidth: 1,
        lineStyle: 4,
        axisLabelVisible: true,
        title: '昨收'
      })
    }
  } else if (prevCloseLine) {
    candleSeries.removePriceLine(prevCloseLine)
    prevCloseLine = null
  }

  // 近 30 根的高低参考线：数据不足 30 根时仍按实际根数画，避免短序列完全不显示
  const range = props.show30DayHL && bars && bars.length > 0 ? lastBars(bars, 30) : []
  if (range.length > 0) {
    let high = -Infinity
    let low = Infinity
    for (const bar of range) {
      if (bar.high > high) high = bar.high
      if (bar.low < low) low = bar.low
    }
    if (Number.isFinite(high)) {
      if (high30Line) {
        high30Line.applyOptions({ price: high })
      } else {
        high30Line = candleSeries.createPriceLine({
          price: high,
          color: '#d96ba0',
          lineWidth: 1,
          lineStyle: 3,
          axisLabelVisible: true,
          title: '近30高'
        })
      }
    }
    if (Number.isFinite(low)) {
      if (low30Line) {
        low30Line.applyOptions({ price: low })
      } else {
        low30Line = candleSeries.createPriceLine({
          price: low,
          color: '#4fb8c0',
          lineWidth: 1,
          lineStyle: 3,
          axisLabelVisible: true,
          title: '近30低'
        })
      }
    }
  } else {
    if (high30Line) {
      candleSeries.removePriceLine(high30Line)
      high30Line = null
    }
    if (low30Line) {
      candleSeries.removePriceLine(low30Line)
      low30Line = null
    }
  }
}

/** 取序列末尾 n 根（序列已按日期升序排好）。 */
function lastBars(bars: readonly KlineItem[], n: number): KlineItem[] {
  return bars.length <= n ? [...bars] : bars.slice(bars.length - n)
}

watch(
  () => [props.data, props.period, props.maPeriods, props.show30DayHL],
  () => render(),
  { deep: false }
)

watch(() => [props.costPrice, props.prevClose, props.show30DayHL], () => {
  // 参考线开关变了但 K 线没变时，render 不会被 props.data 触发，这里单独补一次
  const sorted = [...(props.data ?? [])].sort((a, b) =>
    a.date < b.date ? -1 : a.date > b.date ? 1 : 0
  )
  renderPriceLines(sorted)
})

onMounted(() => {
  createOrUpdateChart()
  syncMaSeries()
  render()
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  if (lockFrame) {
    cancelAnimationFrame(lockFrame)
    lockFrame = 0
  }
  wheelTarget?.removeEventListener('wheel', handleWheel)
  wheelTarget = null
  chart?.remove()
  chart = null
  candleSeries = null
  volumeSeries = null
  maSeries.clear()
  builtPeriods = []
  costLine = null
  prevCloseLine = null
  high30Line = null
  low30Line = null
  fittedKey = ''
  dataLength = 0
})

/** 深浅色切换时整体重绘配色（canvas 拿不到 CSS 变量） */
function applyTheme() {
  const dark = isDarkMode()
  const theme = chartTheme(dark)
  chart?.applyOptions({
    layout: { textColor: theme.text },
    grid: { vertLines: { color: theme.grid }, horzLines: { color: theme.grid } },
    rightPriceScale: { borderColor: theme.border },
    timeScale: { borderColor: theme.border }
  })
  render()
}

defineExpose({ applyTheme })
</script>

<template>
  <div class="kline">
    <div class="kline__legend">
      <span v-for="p in maPeriods" :key="p" class="kline__legend-item" :style="{ color: MA_COLORS[p] }">
        MA{{ p }}
        <b>{{ maValues[p] != null ? maValues[p].toFixed(2) : '--' }}</b>
      </span>
    </div>
    <div ref="container" class="kline__canvas" />
    <div v-if="loading" class="kline__mask"><span class="kline__spinner" /></div>
    <div v-else-if="!data || data.length === 0" class="kline__empty">暂无 K 线数据</div>
  </div>
</template>

<style scoped>
.kline {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 320px;
}

.kline__legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-4);
  padding: 0 0 var(--s-2);
  font-size: var(--t-xs);
  font-variant-numeric: tabular-nums;
}

.kline__legend-item b {
  font-weight: 600;
  margin-left: 2px;
}

.kline__canvas {
  flex: 1;
  min-height: 0;
}

.kline__mask {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  background: color-mix(in srgb, var(--c-surface) 70%, transparent);
}

.kline__spinner {
  width: 22px;
  height: 22px;
  border: 2px solid var(--c-border);
  border-top-color: var(--c-brand);
  border-radius: 50%;
  animation: kline-spin 0.7s linear infinite;
}

@keyframes kline-spin {
  to {
    transform: rotate(360deg);
  }
}

.kline__empty {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  font-size: var(--t-sm);
  color: var(--c-text-faint);
  pointer-events: none;
}
</style>
