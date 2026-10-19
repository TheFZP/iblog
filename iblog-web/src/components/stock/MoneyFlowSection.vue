<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { MoneyFlow, MoneyFlowHistoryItem } from '@/api/stock'
import MoneyFlowChart from './MoneyFlowChart.vue'

/**
 * 「主力资金」区块：当日分档快照 + 近 N 日净流入柱状图。
 *
 * <p>数据是父组件拉的，本组件只负责展示，便于父组件统一做竞态保护与刷新节奏。</p>
 */

const props = withDefaults(
  defineProps<{
    moneyFlow: MoneyFlow | null
    moneyFlowLoading?: boolean
    moneyFlowHistory?: MoneyFlowHistoryItem[] | null
    moneyFlowHistoryLoading?: boolean
    /** 刷新失败但保留了上一次的数据 */
    stale?: boolean
  }>(),
  {
    moneyFlowLoading: false,
    moneyFlowHistory: null,
    moneyFlowHistoryLoading: false,
    stale: false
  }
)

const chartRef = ref<InstanceType<typeof MoneyFlowChart> | null>(null)
let themeObserver: MutationObserver | null = null

/** 四档明细。顺序固定「超大单→大单→中单→小单」，与东财/同花顺一致 */
const flowTiers = computed(() => {
  const mf = props.moneyFlow
  // unavailable 时后端返回的是全 0 的占位对象，照常渲染会显示「四档全是 0」这种假数据
  if (!mf || mf.unavailable) {
    return []
  }
  return [
    { key: 'super', label: '超大单', net: mf.superLargeNet ?? 0, pct: mf.superLargePct ?? 0 },
    { key: 'large', label: '大单', net: mf.largeNet ?? 0, pct: mf.largePct ?? 0 },
    { key: 'medium', label: '中单', net: mf.mediumNet ?? 0, pct: mf.mediumPct ?? 0 },
    { key: 'small', label: '小单', net: mf.smallNet ?? 0, pct: mf.smallPct ?? 0 }
  ]
})

/**
 * 万元 → 展示文本，保留正负号。
 *
 * <p>没有复用 fmtTurnover：它用 `wan >= 1e4` 判断是否换算成「亿」，负值永远进不了亿分支，
 * -12 亿会显示成 "-123456万"。</p>
 */
function fmtWan(v: number | null | undefined): string {
  if (v == null || Number.isNaN(v)) {
    return '--'
  }
  const abs = Math.abs(v)
  const sign = v < 0 ? '-' : ''
  return abs >= 1e4 ? `${sign}${(abs / 1e4).toFixed(2)}亿` : `${sign}${abs.toFixed(0)}万`
}

function fmtSignedWan(v: number): string {
  return v > 0 ? `+${fmtWan(v)}` : fmtWan(v)
}

function fmtPct(v: number | null | undefined): string {
  if (v == null || Number.isNaN(v)) {
    return '--'
  }
  return `${v > 0 ? '+' : ''}${v.toFixed(2)}%`
}

function tone(n: number): string {
  return n > 0 ? 'tone-up' : n < 0 ? 'tone-down' : 'tone-flat'
}

/** 分档占比条宽度（占最大绝对值的比例），让四档的强弱一眼可见 */
const maxAbsNet = computed(() => {
  const max = Math.max(...flowTiers.value.map((t) => Math.abs(t.net)), 1)
  return max
})

onMounted(() => {
  // 深浅色切换时 canvas 拿不到 CSS 变量，必须主动重绘
  themeObserver = new MutationObserver(() => chartRef.value?.applyTheme())
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
})

onBeforeUnmount(() => {
  themeObserver?.disconnect()
  themeObserver = null
})
</script>

<template>
  <section class="mf-section" aria-labelledby="mf-title">
    <div class="mf-section__head">
      <h2 id="mf-title" class="mf-section__title">主力资金</h2>

      <template v-if="moneyFlow && !moneyFlow.unavailable">
        <span class="mf-section__main" :class="tone(moneyFlow.mainNetInflow)">
          {{ fmtSignedWan(moneyFlow.mainNetInflow) }}
        </span>
        <span class="mf-section__pct" :class="tone(moneyFlow.mainNetInflow)">
          {{ fmtPct(moneyFlow.mainNetPct) }}
        </span>
        <!-- current=false 时上游当日还没生成数据，展示的是最近交易日，必须说清楚是哪天 -->
        <span class="mf-section__date">
          {{ moneyFlow.current ? '今日' : moneyFlow.date }}
        </span>
      </template>
      <span v-else-if="moneyFlowLoading" class="mf-section__hint">加载中…</span>
      <!--
        两种「没数据」要分开说：网络原因是暂时的（过会儿刷新就好），
        上游真没这只标的的数据则是这件事到此为止。都说成「暂无」会让人以为股票选错了。
      -->
      <span v-else-if="moneyFlow?.unavailable && moneyFlow.reason === 'network'" class="mf-section__hint">
        上游暂时连不上，稍后刷新再试
      </span>
      <span v-else-if="moneyFlow?.unavailable" class="mf-section__hint">该股暂无资金流数据</span>
      <span v-else class="mf-section__hint">--</span>

      <span v-if="stale" class="mf-section__stale" title="本次刷新失败，展示的是上一次成功的数据">
        旧数据
      </span>
    </div>

    <div v-if="flowTiers.length" class="mf-tiers">
      <div v-for="t in flowTiers" :key="t.key" class="mf-tier">
        <span class="mf-tier__name">{{ t.label }}</span>
        <!--
          柱子从中线向两侧生长：流入向右、流出向左。都从左边起的话，
          读者得先看颜色才知道方向，中线发散位一眼就能看出「进」还是「出」。
        -->
        <span class="mf-tier__bar" :class="{ 'mf-tier__bar--out': t.net < 0 }" aria-hidden="true">
          <i
            class="mf-tier__fill"
            :class="tone(t.net)"
            :style="{ width: `${(Math.abs(t.net) / maxAbsNet) * 50}%` }"
          />
        </span>
        <span class="mf-tier__value" :class="tone(t.net)">{{ fmtSignedWan(t.net) }}</span>
        <span class="mf-tier__pct" :class="tone(t.net)">{{ fmtPct(t.pct) }}</span>
      </div>
    </div>

    <p class="mf-section__note">
      主力 = 超大单 + 大单。净流入为正表示净买入，单位按万元/亿元显示。
    </p>

    <MoneyFlowChart
      ref="chartRef"
      :data="moneyFlowHistory ?? []"
      :loading="moneyFlowHistoryLoading"
    />
  </section>
</template>

<style scoped>
.mf-section {
  display: flex;
  flex-direction: column;
  gap: var(--s-3);
  padding: var(--s-5);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-md);
}

.mf-section__head {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: var(--s-2) var(--s-3);
}

.mf-section__title {
  margin: 0;
  font-size: var(--t-base);
  font-weight: 600;
  color: var(--c-text);
}

.mf-section__main {
  font-size: var(--t-xl);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
}

.mf-section__pct {
  font-size: var(--t-md);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.mf-section__date,
.mf-section__hint {
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.mf-section__stale {
  font-size: var(--t-xs);
  color: var(--c-text-muted);
  border: 1px solid var(--c-border-strong);
  border-radius: var(--r-sm);
  padding: 0 5px;
  line-height: 16px;
}

.mf-tiers {
  display: flex;
  flex-direction: column;
  gap: var(--s-2);
}

.mf-tier {
  display: grid;
  grid-template-columns: 52px minmax(0, 1fr) 92px 76px;
  align-items: center;
  gap: var(--s-3);
  font-variant-numeric: tabular-nums;
}

.mf-tier__name {
  font-size: var(--t-sm);
  color: var(--c-text-muted);
}

/* 占比条：一半向左一半向右，正负值方向一眼可辨 */
.mf-tier__bar {
  position: relative;
  height: 6px;
  border-radius: 3px;
  background: var(--c-surface-sunken);
  overflow: hidden;
}

/* 中线：净流入/净流出的界标 */
.mf-tier__bar::after {
  content: '';
  position: absolute;
  top: -1px;
  bottom: -1px;
  left: 50%;
  width: 1px;
  background: var(--c-border-strong);
}

.mf-tier__fill {
  position: absolute;
  top: 0;
  bottom: 0;
  /* 默认「流入」：从中线向右长 */
  left: 50%;
  border-radius: 3px;
  min-width: 2px;
  transition: width var(--ease) 220ms;
}

/* 「流出」：从中线向左长 —— 用 right 定位让宽度朝左展开 */
.mf-tier__bar--out .mf-tier__fill {
  left: auto;
  right: 50%;
}

.mf-tier__fill.tone-up {
  background: var(--stock-up);
}

.mf-tier__fill.tone-down {
  background: var(--stock-down);
}

.mf-tier__value {
  font-size: var(--t-sm);
  font-weight: 600;
  text-align: right;
}

.mf-tier__pct {
  font-size: var(--t-sm);
  font-weight: 600;
  text-align: right;
}

.mf-section__note {
  margin: 0;
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

@media (max-width: 720px) {
  .mf-section {
    padding: var(--s-4);
  }

  .mf-tier {
    grid-template-columns: 48px minmax(0, 1fr) 80px 64px;
    gap: var(--s-2);
  }
}
</style>
