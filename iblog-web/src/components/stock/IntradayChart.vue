<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  createChart,
  createSeriesMarkers,
  ColorType,
  LineSeries,
  HistogramSeries,
  AreaSeries,
  CrosshairMode,
  type IChartApi,
  type ISeriesApi,
  type IPriceLine,
  type ISeriesMarkersPluginApi,
  type SeriesMarker,
  type Time,
  type UTCTimestamp,
  type BusinessDay
} from 'lightweight-charts'
import type { IntradayItem } from '@/api/stock'
import type { T0Marker } from '@/utils/t0Signals'
import { chartTheme, isDarkMode, UP_COLOR, DOWN_COLOR } from '@/utils/stockFormat'

/**
 * 分时图。
 *
 * <p>画三样东西：价格线（带渐变填充）、均价线（黄）、成交量柱。
 * 昨收价画一条水平参考线，价格线在它上方就是涨、下方就是跌。</p>
 *
 * <p>时间是 {@code HH:mm} 字符串，转成当天的时间戳。刻意用 UTC 编码
 * （把东八区的 09:30 当作 UTC 09:30），这样坐标轴显示的刻度就是原始的 HH:mm，
 * 不受浏览器所在时区影响。</p>
 *
 * <p>第四样东西是 T+0 信号标记（{@code markers}），由 {@code utils/t0Signals}
 * 的纯函数算出。标记只能挂在 series 上，且 lightweight-charts 5.x 用
 * {@code createSeriesMarkers} 插件批量渲染——{@code createPriceLine} 一条线只能画一个价位，
 * 画不了「一分钟一个点」的密集标记。</p>
 */

const props = withDefaults(
  defineProps<{
    data: IntradayItem[]
    prevClose?: number | null
    loading?: boolean
    /** T+0 信号标记，空数组即不画 */
    markers?: T0Marker[]
  }>(),
  { prevClose: null, loading: false, markers: () => [] }
)

/**
 * 诱多标记的橙色。
 *
 * <p>涨用红、跌用绿是 A 股口径，`stockFormat` 里已经有 {@code UP_COLOR}/{@code DOWN_COLOR}；
 * 「疑似诱多」既不是涨也不是跌，需要第三个色，本地定义而不改 {@code stockFormat}
 * （那个文件被多个图表共用，本次改动不碰它）。</p>
 */
const TRAP_COLOR = { light: '#c76b12', dark: '#ffa94d' }

const container = ref<HTMLDivElement | null>(null)

let chart: IChartApi | null = null
let priceSeries: ISeriesApi<'Area'> | null = null
let avgSeries: ISeriesApi<'Line'> | null = null
let volumeSeries: ISeriesApi<'Histogram'> | null = null
/** 标记插件的句柄：重建标记只能通过它的 setMarkers()，没有别的入口 */
let markersPlugin: ISeriesMarkersPluginApi<Time> | null = null
let resizeObserver: ResizeObserver | null = null

/** HH:mm → 当天 UTC 时间戳（秒） */
function toTime(hhmm: string): UTCTimestamp | BusinessDay {
  const [h, m] = hhmm.split(':')
  const d = new Date()
  return Math.floor(
    Date.UTC(d.getFullYear(), d.getMonth(), d.getDate(), Number(h) || 0, Number(m) || 0) / 1000
  ) as UTCTimestamp
}

function buildChart() {
  if (!container.value) {
    return
  }
  const dark = isDarkMode()
  const theme = chartTheme(dark)
  const up = dark ? UP_COLOR.dark : UP_COLOR.light
  const down = dark ? DOWN_COLOR.dark : DOWN_COLOR.light

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
        scaleMargins: { top: 0.1, bottom: 0.28 }
      },
      timeScale: {
        borderColor: theme.border,
        timeVisible: true,
        secondsVisible: false,
        // 分时固定 09:30-15:00，不要让图表右边界随数据缩进
        fixLeftEdge: true
      },
      handleScroll: { vertTouchDrag: false }
    })

    priceSeries = chart.addSeries(AreaSeries, {
      lineColor: up,
      lineWidth: 2,
      topColor: dark ? 'rgba(255, 107, 94, 0.28)' : 'rgba(217, 48, 37, 0.22)',
      bottomColor: 'rgba(0, 0, 0, 0)',
      priceLineVisible: false,
      priceFormat: { type: 'price', precision: 2, minMove: 0.01 }
    })

    avgSeries = chart.addSeries(LineSeries, {
      color: theme.avgLine,
      lineWidth: 1,
      priceLineVisible: false,
      lastValueVisible: false,
      crosshairMarkerVisible: false
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
  }

  // 价格线颜色要跟着涨跌方向：全天在昨收上方用红，下方用绿
  const items = props.data ?? []
  if (items.length > 0 && props.prevClose != null && props.prevClose > 0) {
    const lastPrice = items[items.length - 1].price
    const rising = lastPrice >= props.prevClose
    priceSeries?.applyOptions({
      lineColor: rising ? up : down,
      topColor: rising
        ? dark
          ? 'rgba(255, 107, 94, 0.28)'
          : 'rgba(217, 48, 37, 0.22)'
        : dark
          ? 'rgba(69, 194, 111, 0.26)'
          : 'rgba(20, 128, 60, 0.2)'
    })
  }
}

function render() {
  if (!chart) {
    return
  }
  const items = props.data ?? []
  if (items.length === 0) {
    priceSeries?.setData([])
    avgSeries?.setData([])
    volumeSeries?.setData([])
    // 没有数据就没有可挂的位置，标记一并清掉，否则旧标记会留在空图上
    markersPlugin?.setMarkers([])
    return
  }

  buildChart()
  const prev = props.prevClose ?? 0
  const theme = chartTheme(isDarkMode())

  priceSeries?.setData(items.map((i) => ({ time: toTime(i.time), value: i.price })))
  // 均价可能为 0（开盘瞬间），跳掉避免把线拉到 0
  avgSeries?.setData(
    items.filter((i) => i.avgPrice > 0).map((i) => ({ time: toTime(i.time), value: i.avgPrice }))
  )
  volumeSeries?.setData(
    items.map((i) => ({
      time: toTime(i.time),
      value: i.volume,
      // 分时成交量按「这一分钟价格 vs 上一分钟」着色，比按昨收着色更能反映当下的多空
      color: (i.price >= prev ? theme.upVolume : theme.downVolume) as string
    }))
  )

  chart.timeScale().fitContent()
}

/** 昨收横线：单独用价格线挂在均价系列上（分时图没有 K 线系列可用） */
let refLine: IPriceLine | null = null
function renderRefLine() {
  if (!avgSeries) {
    return
  }
  const prev = props.prevClose ?? 0
  if (refLine) {
    avgSeries.removePriceLine(refLine)
    refLine = null
  }
  if (prev > 0) {
    refLine = avgSeries.createPriceLine({
      price: prev,
      color: chartTheme(isDarkMode()).refLine,
      lineWidth: 1,
      lineStyle: 4,
      axisLabelVisible: true,
      title: '昨收'
    })
  }
}

/**
 * 标记的呈现方式（形状 / 上下位置 / 色彩基调）。
 *
 * <p>抽成纯函数而不是写在 {@link renderMarkers} 的 switch 里：一个是穷尽性由类型检查保证
 * （返回 undefined 表示这类不画），另一个是配色依赖主题、形状依赖语义，混在一起改一处要看两处。</p>
 *
 * <p>形状语义：看多用向上箭头画在柱下方、看空用向下箭头画在上方，避免压住价格线；
 * 「疑似诱多」用圆点——它不是方向信号，只是提醒这里可能有人在派发。</p>
 */
function markerShape(kind: T0Marker['kind']):
  | {
      position: 'aboveBar' | 'belowBar'
      shape: 'arrowUp' | 'arrowDown' | 'circle'
      tone: 'up' | 'down' | 'trap'
    }
  | undefined {
  switch (kind) {
    case 'spike-up':
    case 'break-up':
    case 'dry-rally':
      return { position: 'belowBar', shape: 'arrowUp', tone: 'up' }
    case 'spike-down':
    case 'break-down':
    case 'trap-bear':
      return { position: 'aboveBar', shape: 'arrowDown', tone: 'down' }
    case 'trap-bull':
      return { position: 'aboveBar', shape: 'circle', tone: 'trap' }
    default:
      return undefined
  }
}

/**
 * 把 T0 标记转成 lightweight-charts 的 series marker 并重画。
 *
 * <p>时间必须走同一个 {@link toTime}：图表的 x 轴是「HH:mm 当 UTC 编码」的时间戳，
 * 若这里传 {@code 'HH:mm'} 字符串或本地时间戳，lightweight-charts 会抛
 * "time must be..." 并整块渲染失败。</p>
 *
 * <p>标记挂在价格系列上（{@code priceSeries}），因为位置是「某分钟的价格」。
 * 颜色随主题，因此在 {@code applyTheme()} 里也要重画。</p>
 */
function renderMarkers() {
  const list = props.markers ?? []
  if (!priceSeries) {
    return
  }
  if (!markersPlugin) {
    markersPlugin = createSeriesMarkers(priceSeries, [])
  }
  if (list.length === 0) {
    markersPlugin.setMarkers([])
    return
  }

  const dark = isDarkMode()
  const upColor = dark ? UP_COLOR.dark : UP_COLOR.light
  const downColor = dark ? DOWN_COLOR.dark : DOWN_COLOR.light
  const trapColor = dark ? TRAP_COLOR.dark : TRAP_COLOR.light

  // 用 flatMap + 显式返回 undefined 表示「这类不画」：
  // 之前写成 map + switch 漏 case，TS 会推断出 undefined 混进数组（TS2322）。
  const markers: SeriesMarker<Time>[] = list.flatMap((m) => {
    const base = { time: toTime(m.time), text: m.icon }
    const shape = markerShape(m.kind)
    if (!shape) {
      return []
    }
    const color =
      shape.tone === 'up' ? upColor : shape.tone === 'down' ? downColor : trapColor
    return [{ ...base, position: shape.position, color, shape: shape.shape }]
  })

  // 同一 time 只能挂一个 marker，lightweight-charts 遇到重复 time 会抛错；
  // 上游 computeT0Signals 已保证每分钟至多一个标记，这里按 time 去重兜一层。
  const seen = new Set<number>()
  markersPlugin.setMarkers(
    markers.filter((m) => {
      const t = m.time as number
      if (seen.has(t)) {
        return false
      }
      seen.add(t)
      return true
    })
  )
}

watch(
  () => [props.data, props.prevClose],
  () => {
    render()
    renderRefLine()
  },
  { deep: false }
)

watch(
  () => props.markers,
  () => renderMarkers(),
  { deep: false }
)

onMounted(() => {
  buildChart()
  render()
  renderRefLine()
  renderMarkers()
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  // 先摘标记再销毁图表：chart.remove() 之后 series 已失效，再 setMarkers 会抛错
  markersPlugin?.setMarkers([])
  markersPlugin = null
  chart?.remove()
  chart = null
  priceSeries = null
  avgSeries = null
  volumeSeries = null
  refLine = null
})

function applyTheme() {
  const theme = chartTheme(isDarkMode())
  chart?.applyOptions({
    layout: { textColor: theme.text },
    grid: { vertLines: { color: theme.grid }, horzLines: { color: theme.grid } },
    rightPriceScale: { borderColor: theme.border },
    timeScale: { borderColor: theme.border }
  })
  render()
  renderRefLine()
  renderMarkers()
}

defineExpose({ applyTheme })
</script>

<template>
  <div class="intraday">
    <div ref="container" class="intraday__canvas" />
    <div v-if="loading" class="intraday__mask"><span class="intraday__spinner" /></div>
    <div v-else-if="!data || data.length === 0" class="intraday__empty">暂无分时数据（非交易日或已收盘）</div>
  </div>
</template>

<style scoped>
.intraday {
  position: relative;
  height: 100%;
  min-height: 320px;
}

.intraday__canvas {
  height: 100%;
}

.intraday__mask {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  background: color-mix(in srgb, var(--c-surface) 70%, transparent);
}

.intraday__spinner {
  width: 22px;
  height: 22px;
  border: 2px solid var(--c-border);
  border-top-color: var(--c-brand);
  border-radius: 50%;
  animation: intraday-spin 0.7s linear infinite;
}

@keyframes intraday-spin {
  to {
    transform: rotate(360deg);
  }
}

.intraday__empty {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  font-size: var(--t-sm);
  color: var(--c-text-faint);
  pointer-events: none;
}
</style>
