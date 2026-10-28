<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { fetchQuote, type StockQuote } from '@/api/stock'
import { assetClassOf } from '@/utils/portfolio'
import { fmtNum, fmtSigned, toneClass } from '@/utils/stockFormat'
import TechTab from '@/components/stock/analysis/TechTab.vue'
import ChipTab from '@/components/stock/analysis/ChipTab.vue'
import LevelTab from '@/components/stock/analysis/LevelTab.vue'
import BacktestTab from '@/components/stock/analysis/BacktestTab.vue'

/**
 * 个股技术分析页：四个 Tab 共用同一只股票，但各自拉数、各自报错。
 *
 * <p>Tab 状态放在本地 ref 而不是路由：Lead 不打算为每个 Tab 建独立 path，
 * 写进 query 会让「切 Tab」变成一次路由跳转，返回按钮的行为也跟着变。</p>
 *
 * <p>父页面只负责「股票是谁 + 顶部报价 + 当前哪个 Tab」；K 线由各 Tab 自己拉，
 * 这样某个 Tab 的请求失败不会把另外三个一起拖白。</p>
 */

const TABS = [
  { key: 'tech', label: '技术指标' },
  { key: 'chip', label: '筹码分布' },
  { key: 'level', label: '支撑阻力' },
  { key: 'backtest', label: '信号回测' }
] as const
type TabKey = (typeof TABS)[number]['key']

const route = useRoute()
const code = computed(() => String(route.params.code ?? ''))
const tab = ref<TabKey>('tech')

const quote = ref<StockQuote | null>(null)
const quoteLoading = ref(false)
const quoteError = ref('')
/** 点「刷新」时自增：Tab 组件以它为 key 重建，顺带重拉自己的 K 线 */
const refreshKey = ref(0)
let quoteSeq = 0

const priceDigits = computed(() => (assetClassOf(code.value) === '港股' ? 3 : 2))

const priceText = computed(() => {
  const q = quote.value
  return q ? `${q.currency || '¥'}${fmtNum(q.price, priceDigits.value)}` : '--'
})

async function loadQuote() {
  const target = code.value
  if (!target) {
    quote.value = null
    return
  }
  const my = ++quoteSeq
  quoteLoading.value = true
  quoteError.value = ''
  try {
    const data = await fetchQuote(target)
    if (my !== quoteSeq) {
      return
    }
    quote.value = data
  } catch (err) {
    if (my !== quoteSeq) {
      return
    }
    // 报价只是页头装饰：失败时保留旧数据，指标区照常可用
    quoteError.value = err instanceof Error ? err.message : String(err)
  } finally {
    if (my === quoteSeq) {
      quoteLoading.value = false
    }
  }
}

function refresh() {
  refreshKey.value += 1
  void loadQuote()
}

function quoteTimeText(value: string | undefined): string {
  if (!value) {
    return ''
  }
  return value.replace('T', ' ').slice(0, 16)
}

onMounted(() => {
  void loadQuote()
})

watch(code, () => {
  void loadQuote()
})
</script>

<template>
  <div class="analysis-page">
    <div class="layout">
      <RouterLink :to="`/stock/${code}`" class="back-link">← 返回个股</RouterLink>

      <header class="page-head">
        <div class="page-head__main">
          <h1 class="page-title">
            {{ quote?.name || code }}
            <span class="page-title__code">{{ code }}</span>
          </h1>
          <p class="quote-line">
            <span class="quote-line__price" :class="toneClass(quote?.changePct)">
              {{ priceText }}
            </span>
            <span class="quote-line__change" :class="toneClass(quote?.changePct)">
              {{ quote ? `${fmtSigned(quote.change)} / ${fmtSigned(quote.changePct)}%` : '--' }}
            </span>
            <span v-if="quote?.suspended" class="quote-line__flag">停牌</span>
            <span v-else-if="quoteLoading && !quote" class="quote-line__hint">报价加载中…</span>
            <span v-else-if="quoteError" class="quote-line__hint quote-line__hint--error">{{ quoteError }}</span>
            <span v-if="quoteTimeText(quote?.quoteTime)" class="quote-line__time">
              {{ quoteTimeText(quote?.quoteTime) }}
            </span>
          </p>
        </div>
        <button type="button" class="btn btn--ghost" :disabled="quoteLoading" @click="refresh">
          刷新
        </button>
      </header>

      <nav class="tabs" role="tablist" aria-label="技术分析视图">
        <button
          v-for="item in TABS"
          :key="item.key"
          type="button"
          role="tab"
          class="tabs__btn"
          :class="{ 'is-active': tab === item.key }"
          :aria-selected="tab === item.key"
          @click="tab = item.key"
        >
          {{ item.label }}
        </button>
      </nav>

      <div class="tab-body">
        <TechTab v-if="tab === 'tech'" :key="`tech-${refreshKey}`" :code="code" />
        <ChipTab v-else-if="tab === 'chip'" :key="`chip-${refreshKey}`" :code="code" />
        <LevelTab v-else-if="tab === 'level'" :key="`level-${refreshKey}`" :code="code" />
        <BacktestTab v-else :key="`backtest-${refreshKey}`" :code="code" />
      </div>

      <p class="page-note">
        页面基于日 K 数据做指标计算与历史统计，不构成投资建议。指标参数与口径见各 Tab 底部说明。
      </p>
    </div>
  </div>
</template>

<style scoped>
.analysis-page {
  padding-bottom: var(--s-8);
}

.back-link {
  display: inline-block;
  margin-bottom: var(--s-3);
  font-size: var(--t-sm);
  color: var(--c-text-muted);
  text-decoration: none;
}

.back-link:hover {
  color: var(--c-brand);
}

.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--s-3) var(--s-6);
  margin-bottom: var(--s-4);
}

.page-title {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: var(--s-2);
  margin: 0;
  font-size: var(--t-2xl);
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--c-text);
}

.page-title__code {
  font-family: var(--f-mono);
  font-size: var(--t-sm);
  font-weight: 500;
  color: var(--c-text-muted);
}

.quote-line {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: var(--s-2) var(--s-3);
  margin: var(--s-2) 0 0;
  font-variant-numeric: tabular-nums;
}

.quote-line__price {
  font-size: var(--t-xl);
  font-weight: 700;
}

.quote-line__change {
  font-size: var(--t-sm);
  font-weight: 600;
}

.quote-line__flag {
  padding: 0 6px;
  font-size: var(--t-xs);
  color: var(--c-accent);
  background: color-mix(in srgb, var(--c-accent) 10%, transparent);
  border-radius: var(--r-sm);
}

.quote-line__hint,
.quote-line__time {
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.quote-line__hint--error {
  color: var(--stock-up);
}

.tabs {
  display: flex;
  gap: var(--s-1);
  overflow-x: auto;
  border-bottom: 1px solid var(--c-border);
}

.tabs__btn {
  appearance: none;
  flex-shrink: 0;
  margin-bottom: -1px;
  padding: var(--s-3) var(--s-4);
  font: inherit;
  font-size: var(--t-sm);
  font-weight: 600;
  color: var(--c-text-muted);
  background: transparent;
  border: 0;
  border-bottom: 2px solid transparent;
  cursor: pointer;
}

.tabs__btn:hover {
  color: var(--c-text);
}

.tabs__btn.is-active {
  color: var(--c-brand);
  border-bottom-color: var(--c-brand);
}

.tabs__btn:focus-visible {
  outline: 2px solid var(--c-brand-ring);
  outline-offset: -2px;
}

.tab-body {
  padding-top: var(--s-5);
}

.page-note {
  margin: var(--s-6) 0 0;
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

@media (max-width: 720px) {
  .page-head {
    align-items: flex-start;
  }

  .tab-body {
    padding-top: var(--s-4);
  }
}
</style>
