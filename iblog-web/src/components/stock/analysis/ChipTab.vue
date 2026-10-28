<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { fetchQuote, type StockQuote } from '@/api/stock'
import {
  CHIP_MAX_BARS,
  calcChipDistribution,
  chipOptionsFromQuote,
  type ChipDistribution,
  type ChipDistributionOptions
} from '@/utils/chipDistribution'
import { fmtNum } from '@/utils/stockFormat'
import { useKlineBars } from './useKlineBars'

/**
 * 筹码分布 Tab。
 *
 * <p>换手率衰减必须由行情快照提供：日 K 只有「最新一根的量」和「当日换手率」同口径，
 * 拿这个反推历史各根的换手，所以这里额外拉一次 quote；quote 失败时降级为「未衰减」，
 * 图表照画，但界面必须明确标注，否则用户会把已经置换掉的老筹码当成现在的持仓成本。</p>
 */

const props = defineProps<{ code: string }>()

const { bars, loading, error, reload } = useKlineBars(computed(() => props.code), CHIP_MAX_BARS)

const quote = ref<StockQuote | null>(null)
const quoteError = ref('')
let quoteSeq = 0

async function loadQuote() {
  const target = props.code
  if (!target) {
    return
  }
  const my = ++quoteSeq
  try {
    const data = await fetchQuote(target)
    if (my !== quoteSeq) {
      return
    }
    quote.value = data
    quoteError.value = ''
  } catch (err) {
    if (my !== quoteSeq) {
      return
    }
    quoteError.value = err instanceof Error ? err.message : String(err)
  }
}

const steps = ref(60)
const smoothWindow = ref(3)
const decayCoefficient = ref(1)

function clampNumber(value: unknown, min: number, max: number, fallback: number, integer: boolean): number {
  const n = Number(value)
  if (!Number.isFinite(n)) {
    return fallback
  }
  const bounded = Math.min(max, Math.max(min, n))
  return integer ? Math.round(bounded) : Math.round(bounded * 100) / 100
}

function onStepsChange() {
  steps.value = clampNumber(steps.value, 20, 200, 60, true)
}

function onSmoothChange() {
  smoothWindow.value = clampNumber(smoothWindow.value, 1, 10, 3, true)
}

function onDecayChange() {
  decayCoefficient.value = clampNumber(decayCoefficient.value, 0, 2, 1, false)
}

const chip = computed<ChipDistribution | null>(() => {
  if (!bars.value.length) {
    return null
  }
  // 参数是纯前端重算（同步 + 毫秒级），不需要重新请求 K 线
  const options: ChipDistributionOptions = {
    steps: steps.value,
    smoothWindow: smoothWindow.value,
    decayCoefficient: decayCoefficient.value,
    ...chipOptionsFromQuote(quote.value?.turnoverRate, 'day')
  }
  return calcChipDistribution(bars.value, options)
})

/** 图从高价位往低价位画：视觉上「上高下低」，与价格轴一致 */
const rows = computed(() => {
  const c = chip.value
  if (!c) {
    return []
  }
  return c.distribution.map((point, index) => ({ point, index })).reverse()
})

const currentIndex = computed(() => {
  const c = chip.value
  if (!c) {
    return -1
  }
  let best = Infinity
  let idx = -1
  c.distribution.forEach((point, index) => {
    const distance = Math.abs(point.price - c.currentPrice)
    if (distance < best) {
      best = distance
      idx = index
    }
  })
  return idx
})

const maxRatio = computed(() => {
  const c = chip.value
  if (!c) {
    return 0
  }
  return c.distribution.reduce((max, point) => Math.max(max, point.ratio), 0)
})

/** 获利盘：现价及以下的筹码占比。纯函数没给这个字段，只能按分布自己求和 */
const profitRatio = computed(() => {
  const c = chip.value
  if (!c) {
    return null
  }
  const total = c.distribution.reduce((sum, point) => sum + point.ratio, 0)
  const held = c.distribution
    .filter((point) => point.price <= c.currentPrice)
    .reduce((sum, point) => sum + point.ratio, 0)
  return total > 0 ? (held / total) * 100 : null
})

const costRows = computed(() => {
  const c = chip.value
  if (!c) {
    return []
  }
  return [
    { label: 'COST5', value: c.costLevels.COST5 },
    { label: 'COST15', value: c.costLevels.COST15 },
    { label: 'COST50', value: c.costLevels.COST50 },
    { label: 'COST85', value: c.costLevels.COST85 },
    { label: 'COST95', value: c.costLevels.COST95 }
  ]
})

const costPositionText = computed(() => {
  const c = chip.value
  if (!c) {
    return ''
  }
  const levels = costRows.value
    .filter((row): row is { label: string; value: number } => typeof row.value === 'number' && Number.isFinite(row.value))
    .map((row) => ({ label: row.label.replace('COST', ''), value: row.value }))
    .sort((a, b) => a.value - b.value)
  if (levels.length === 0) {
    return ''
  }
  const price = c.currentPrice
  if (price < levels[0].value) {
    return '现价低于最低成本分位，绝大多数持有者处于浮亏。'
  }
  if (price >= levels[levels.length - 1].value) {
    return '现价高于最高成本分位，绝大多数持有者处于浮盈。'
  }
  for (let i = 0; i < levels.length - 1; i++) {
    if (price >= levels[i].value && price < levels[i + 1].value) {
      return `现价位于 ${levels[i].label}% 与 ${levels[i + 1].label}% 成本之间，约 ${fmtNum(profitRatio.value, 1)}% 的筹码处于浮盈。`
    }
  }
  return ''
})

const decayText = computed(() => {
  const c = chip.value
  if (!c) {
    return ''
  }
  if (c.decayed && c.decayTurnoverRate != null) {
    return `已启用（按最新换手率 ${fmtNum(c.decayTurnoverRate, 2)}% 折算历史各根）`
  }
  return '未启用（缺换手率或衰减系数为 0，历史筹码未折损）'
})

onMounted(() => {
  void loadQuote()
})

watch(
  () => props.code,
  () => {
    void loadQuote()
  }
)
</script>

<template>
  <div class="chip-tab">
    <div class="params">
      <label class="params__field">
        <span>档数</span>
        <input
          v-model.number="steps"
          type="number"
          min="20"
          max="200"
          step="10"
          class="params__input"
          @change="onStepsChange"
        />
      </label>
      <label class="params__field">
        <span>平滑窗口</span>
        <input
          v-model.number="smoothWindow"
          type="number"
          min="1"
          max="10"
          step="1"
          class="params__input"
          @change="onSmoothChange"
        />
      </label>
      <label class="params__field">
        <span>衰减系数</span>
        <input
          v-model.number="decayCoefficient"
          type="number"
          min="0"
          max="2"
          step="0.1"
          class="params__input"
          @change="onDecayChange"
        />
      </label>
      <button type="button" class="params__btn" :disabled="loading" @click="reload()">重新拉取</button>
    </div>

    <p v-if="quoteError" class="hint hint--warn">
      换手率获取失败，换手衰减已降级为未启用：{{ quoteError }}
    </p>
    <p v-if="error && bars.length" class="hint hint--error">{{ error }}</p>

    <div v-if="loading && !bars.length" class="state">K 线加载中…</div>

    <div v-else-if="error && !bars.length" class="state state--error">
      加载失败：{{ error }}
    </div>

    <div v-else-if="!chip" class="state">
      K 线不足 5 根或价格区间退化，无法计算筹码分布（当前 {{ bars.length }} 根）。
    </div>

    <div v-else class="chip-grid">
      <section class="card chart-card">
        <header class="card__head">
          <h2 class="card__title">筹码分布</h2>
          <span class="card__extra">共 {{ chip.distribution.length }} 档</span>
        </header>

        <dl class="summary">
          <div class="summary__item">
            <dt>平均成本</dt>
            <dd>{{ fmtNum(chip.avgCost, 2) }}</dd>
          </div>
          <div class="summary__item">
            <dt>获利盘</dt>
            <dd>{{ fmtNum(profitRatio, 1) }}%</dd>
          </div>
          <div class="summary__item">
            <dt>筹码峰</dt>
            <dd>{{ fmtNum(chip.peakPrice, 2) }}</dd>
          </div>
          <div class="summary__item">
            <dt>峰值占比</dt>
            <dd>{{ fmtNum(chip.peakRatio * 100, 1) }}%</dd>
          </div>
        </dl>
        <p class="card__note">当前价 {{ fmtNum(chip.currentPrice, 2) }}；换手率衰减：{{ decayText }}</p>

        <div class="bars" role="img" aria-label="筹码按价格档位的分布">
          <div
            v-for="row in rows"
            :key="row.point.price"
            class="bar"
            :class="{ 'bar--current': row.index === currentIndex }"
          >
            <span class="bar__price">{{ fmtNum(row.point.price, 2) }}</span>
            <span class="bar__track">
              <i
                class="bar__fill"
                :style="{ width: maxRatio > 0 ? `${(row.point.ratio / maxRatio) * 100}%` : '0%' }"
              />
            </span>
            <span class="bar__ratio">{{ fmtNum(row.point.ratio * 100, 1) }}%</span>
          </div>
        </div>

        <p class="card__note">
          筹码按成交量在当日高低区间内、以收盘价为中心做三角分布摊到价格档，逐根累积；
          启用换手率衰减时越久远的历史筹码权重越低。这是估算，不是真实持仓成本。
        </p>
      </section>

      <section class="card">
        <header class="card__head">
          <h2 class="card__title">成本分位</h2>
        </header>
        <table class="cost-table">
          <thead>
            <tr>
              <th scope="col">分位</th>
              <th scope="col">价格</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in costRows" :key="row.label">
              <td>{{ row.label.replace('COST', '') }}%</td>
              <td>{{ row.value == null ? '--' : fmtNum(row.value, 2) }}</td>
            </tr>
          </tbody>
        </table>
        <p class="card__hint">{{ costPositionText }}</p>
        <p class="card__note">
          COST5/15/50/85/95 = 按价格升序累积筹码占比跨过 5%/15%/50%/85%/95% 时对应的价位。
        </p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.chip-tab {
  display: flex;
  flex-direction: column;
  gap: var(--s-4);
}

.params {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--s-3) var(--s-4);
  padding: var(--s-3) var(--s-4);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-md);
}

.params__field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.params__input {
  width: 96px;
  padding: 6px 8px;
  font: inherit;
  font-size: var(--t-sm);
  font-variant-numeric: tabular-nums;
  color: var(--c-text);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-sm);
}

.params__input:focus-visible {
  outline: 2px solid var(--c-brand-ring);
  outline-offset: 1px;
}

.params__btn {
  margin-left: auto;
  padding: 6px 12px;
  font: inherit;
  font-size: var(--t-sm);
  color: var(--c-brand);
  background: none;
  border: 1px solid var(--c-border);
  border-radius: var(--r-sm);
  cursor: pointer;
}

.params__btn:disabled {
  opacity: 0.5;
  cursor: default;
}

.hint {
  margin: 0;
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.hint--warn {
  color: var(--c-accent);
}

.hint--error {
  color: var(--stock-up);
}

.state {
  padding: var(--s-10) var(--s-4);
  text-align: center;
  font-size: var(--t-sm);
  color: var(--c-text-muted);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-md);
}

.state--error {
  color: var(--stock-up);
}

.chip-grid {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
  gap: var(--s-4);
  align-items: start;
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
  gap: var(--s-2);
}

.card__title {
  margin: 0;
  font-size: var(--t-base);
  font-weight: 600;
  color: var(--c-text);
}

.card__extra {
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.card__hint {
  margin: 0;
  font-size: var(--t-sm);
  color: var(--c-text-secondary);
}

.card__note {
  margin: 0;
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--s-2);
  margin: 0;
}

.summary__item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--s-2) var(--s-3);
  background: var(--c-surface-sunken);
  border-radius: var(--r-sm);
}

.summary__item dt {
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.summary__item dd {
  margin: 0;
  font-size: var(--t-sm);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.bars {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 520px;
  overflow-y: auto;
  padding-right: 4px;
}

.bar {
  display: grid;
  grid-template-columns: 56px minmax(0, 1fr) 52px;
  align-items: center;
  gap: var(--s-2);
  font-size: var(--t-xs);
  font-variant-numeric: tabular-nums;
}

.bar--current {
  font-weight: 700;
}

.bar--current .bar__price {
  color: var(--c-brand);
}

.bar__price {
  color: var(--c-text-muted);
  text-align: right;
}

.bar__track {
  position: relative;
  height: 7px;
  overflow: hidden;
  background: var(--c-surface-sunken);
  border-radius: 3px;
}

.bar__fill {
  display: block;
  height: 100%;
  background: color-mix(in srgb, var(--c-brand) 55%, transparent);
  border-radius: 3px;
}

.bar--current .bar__track {
  outline: 1px solid var(--c-brand);
  outline-offset: 1px;
}

.bar--current .bar__fill {
  background: var(--c-brand);
}

.bar__ratio {
  text-align: right;
  color: var(--c-text-secondary);
}

.cost-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--t-sm);
  font-variant-numeric: tabular-nums;
}

.cost-table th,
.cost-table td {
  padding: 6px var(--s-2);
  text-align: left;
  border-bottom: 1px solid var(--c-border);
}

.cost-table th {
  font-size: var(--t-xs);
  font-weight: 600;
  color: var(--c-text-muted);
}

.cost-table td:last-child,
.cost-table th:last-child {
  text-align: right;
}

@media (max-width: 720px) {
  .chip-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .card {
    padding: var(--s-4);
  }

  .bar {
    grid-template-columns: 52px minmax(0, 1fr) 48px;
  }
}
</style>
