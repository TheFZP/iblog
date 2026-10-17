<script setup lang="ts">
import { computed } from 'vue'
import type { OrderBook } from '@/api/stock'
import { fmtNum, toneClass } from '@/utils/stockFormat'

/**
 * 五档盘口。
 *
 * <p>数据来自腾讯行情字段 [9..28]，与现价同一次请求就能拿到，不需要额外接口。</p>
 *
 * <p><b>为什么没有成交明细</b>：逐笔数据源都不稳定 —— 腾讯的 DealController 已下线
 * （固定返回 {@code {"code":11,"msg":"Can't load controller:DealController"}}），
 * 东财的 {@code push2/api/qt/stock/details/get} 会对本机直接断连（风控），
 * 新浪的明细页是 JS 动态渲染且 JSON 接口返回 {@code Service not found}。
 * 与其放一个经常空白的模块，不如不做。</p>
 */

const props = withDefaults(
  defineProps<{
    book: OrderBook | null
    /** 昨收，用于给价格着色 */
    prevClose?: number | null
    loading?: boolean
  }>(),
  { prevClose: null, loading: false }
)

/** 卖盘按「卖五→卖一」自上而下，买盘「买一→买五」，与行情软件一致 */
const askRows = computed(() => props.book?.asks ?? [])
const bidRows = computed(() => props.book?.bids ?? [])

/** 每档量占比条：以五档最大量为基准，让买卖力量对比一眼可见 */
const maxVolume = computed(() => {
  const all = [...askRows.value, ...bidRows.value].map((l) => l.volume)
  return all.length > 0 ? Math.max(...all) : 0
})

function barWidth(volume: number): string {
  if (maxVolume.value <= 0) {
    return '0%'
  }
  return `${Math.max(2, (volume / maxVolume.value) * 100)}%`
}

/** 相对昨收的涨跌，决定价格文字颜色 */
function priceTone(price: number): string {
  if (!props.prevClose) {
    return 'tone-flat'
  }
  return toneClass(price - props.prevClose)
}

/** 买卖力量对比：总买量 / 总卖量 */
const ratio = computed(() => {
  const bidSum = bidRows.value.reduce((s, l) => s + l.volume, 0)
  const askSum = askRows.value.reduce((s, l) => s + l.volume, 0)
  const total = bidSum + askSum
  return {
    bidSum,
    askSum,
    /** 买盘占比 0-100 */
    bidPct: total > 0 ? (bidSum / total) * 100 : 50
  }
})
</script>

<template>
  <section class="depth">
    <header class="depth__head">
      <h3 class="depth__title">五档盘口</h3>
      <span v-if="book?.available" class="depth__ratio">
        委比 <b :class="ratio.bidPct >= 50 ? 'tone-up' : 'tone-down'">
          {{ (ratio.bidPct - 50).toFixed(1) }}%
        </b>
      </span>
    </header>

    <div v-if="!book || !book.available" class="depth__empty">
      港股暂不提供五档盘口
    </div>

    <template v-else>
      <ul class="depth__rows">
        <li v-for="(lv, i) in askRows" :key="`ask-${i}`" class="depth__row">
          <span class="depth__label">卖{{ askRows.length - i }}</span>
          <span class="depth__price" :class="priceTone(lv.price)">{{ fmtNum(lv.price) }}</span>
          <span class="depth__vol">
            <span class="depth__bar depth__bar--ask" :style="{ width: barWidth(lv.volume) }" />
            <span class="depth__vol-num">{{ fmtNum(lv.volume, 0) }}</span>
          </span>
        </li>
      </ul>

      <div class="depth__divider" />

      <ul class="depth__rows">
        <li v-for="(lv, i) in bidRows" :key="`bid-${i}`" class="depth__row">
          <span class="depth__label">买{{ i + 1 }}</span>
          <span class="depth__price" :class="priceTone(lv.price)">{{ fmtNum(lv.price) }}</span>
          <span class="depth__vol">
            <span class="depth__bar depth__bar--bid" :style="{ width: barWidth(lv.volume) }" />
            <span class="depth__vol-num">{{ fmtNum(lv.volume, 0) }}</span>
          </span>
        </li>
      </ul>

      <!-- 买卖力量条 -->
      <div class="depth__meter" :title="`买盘 ${fmtNum(ratio.bidSum, 0)} 手 / 卖盘 ${fmtNum(ratio.askSum, 0)} 手`">
        <span class="depth__meter-bid" :style="{ width: `${ratio.bidPct}%` }" />
        <span class="depth__meter-ask" :style="{ width: `${100 - ratio.bidPct}%` }" />
      </div>
    </template>
  </section>
</template>

<style scoped>
.depth {
  /* 盘口是窄组件，限制宽度避免 5 档被拉得过宽 */
  max-width: 320px;
  min-width: 0;
}

.depth__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--s-3);
}

.depth__title {
  margin: 0;
  font-size: var(--t-sm);
  font-weight: 600;
  color: var(--c-text-secondary);
}

.depth__ratio {
  font-size: var(--t-xs);
  color: var(--c-text-faint);
  font-variant-numeric: tabular-nums;
}

.depth__ratio b {
  font-weight: 600;
}

.depth__empty {
  padding: var(--s-6) 0;
  text-align: center;
  font-size: var(--t-sm);
  color: var(--c-text-faint);
}

.depth__rows {
  list-style: none;
  margin: 0;
  padding: 0;
}

.depth__row {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr) 76px;
  align-items: center;
  gap: var(--s-2);
  padding: 4px 0;
  font-size: var(--t-sm);
  font-variant-numeric: tabular-nums;
}

.depth__label {
  color: var(--c-text-faint);
  font-size: var(--t-xs);
}

.depth__price {
  text-align: right;
  font-weight: 500;
}

/* 量列：柱子从右往左生长，数字压在柱子上方右对齐 */
.depth__vol {
  position: relative;
  display: block;
  height: 20px;
  line-height: 20px;
  overflow: hidden;
  border-radius: var(--r-sm);
}

.depth__vol-num {
  position: relative;
  z-index: 1;
  display: block;
  text-align: right;
  padding-right: 4px;
  color: var(--c-text-muted);
}

.depth__bar {
  position: absolute;
  right: 0;
  top: 0;
  bottom: 0;
  border-radius: var(--r-sm);
  opacity: 0.18;
  pointer-events: none;
  transition: width 0.25s var(--ease-out);
}

.depth__bar--ask {
  background: #d93025;
}

.depth__bar--bid {
  background: #14803c;
}

:root[data-theme='dark'] .depth__bar--ask {
  background: #ff6b5e;
}

:root[data-theme='dark'] .depth__bar--bid {
  background: #45c26f;
}

.depth__divider {
  height: 1px;
  margin: var(--s-2) 0;
  background: var(--c-border);
}

/* 买卖力量条 */
.depth__meter {
  display: flex;
  height: 4px;
  margin-top: var(--s-3);
  border-radius: var(--r-full);
  overflow: hidden;
  background: var(--c-surface-sunken);
}

.depth__meter-bid {
  background: #14803c;
  transition: width 0.3s var(--ease-out);
}

.depth__meter-ask {
  background: #d93025;
  transition: width 0.3s var(--ease-out);
}

:root[data-theme='dark'] .depth__meter-bid {
  background: #45c26f;
}

:root[data-theme='dark'] .depth__meter-ask {
  background: #ff6b5e;
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
</style>
