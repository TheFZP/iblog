<script setup lang="ts">
import { computed } from 'vue'
import { calcSupportResistance } from '@/utils/supportResistance'
import { fmtNum } from '@/utils/stockFormat'
import { useKlineBars } from './useKlineBars'

/**
 * 支撑阻力 Tab。
 *
 * <p>「现价」取最后一根日 K 的收盘价，而不是实时报价：这样与纯函数计算时用的
 * 价格基准完全一致，盘中价格跳动不会让「距支撑 3.2%」在两秒后变成 2.9%。</p>
 */

const props = defineProps<{ code: string }>()

const { bars, loading, error, reload } = useKlineBars(computed(() => props.code), 120)

const levels = computed(() => calcSupportResistance(bars.value))

const latestPrice = computed(() => {
  const list = bars.value
  return list.length ? Number(list[list.length - 1].close) || 0 : 0
})

/** 阻力在阶梯图里上面是「更远」，所以要倒序渲染：远 → 近 → 现价 */
const resistanceTopDown = computed(() => [...levels.value.resistance].reverse())

const nearestSupport = computed(() => levels.value.support[0] ?? null)
const nearestResistance = computed(() => levels.value.resistance[0] ?? null)

const supportDistancePct = computed(() => {
  const level = nearestSupport.value
  if (!level || !(latestPrice.value > 0)) {
    return null
  }
  return ((latestPrice.value - level.price) / latestPrice.value) * 100
})

const resistanceDistancePct = computed(() => {
  const level = nearestResistance.value
  if (!level || !(latestPrice.value > 0)) {
    return null
  }
  return ((level.price - latestPrice.value) / latestPrice.value) * 100
})

/**
 * 盈亏比 = 上方空间 / 下方空间。
 *
 * <p>支撑距离为 0 时直接返回 null：现价就踩在支撑上，分母趋近 0，
 * 算出来会是几十倍这种看起来「极佳」实则会立刻破位的数字。</p>
 */
const riskReward = computed(() => {
  const s = supportDistancePct.value
  const r = resistanceDistancePct.value
  if (s == null || r == null || !(s > 0.0001)) {
    return null
  }
  return r / s
})

const conclusion = computed(() => {
  if (!(latestPrice.value > 0)) {
    return ''
  }
  const parts: string[] = []
  if (nearestSupport.value && supportDistancePct.value != null) {
    parts.push(`距最近支撑 ${fmtNum(supportDistancePct.value, 2)}%（${fmtNum(nearestSupport.value.price, 2)}）`)
  } else {
    parts.push('现价下方无有效支撑')
  }
  if (nearestResistance.value && resistanceDistancePct.value != null) {
    parts.push(`距最近阻力 ${fmtNum(resistanceDistancePct.value, 2)}%（${fmtNum(nearestResistance.value.price, 2)}）`)
  } else {
    parts.push('现价上方无有效阻力')
  }
  const rr = riskReward.value
  parts.push(rr == null ? '盈亏比 —' : `盈亏比 ${fmtNum(rr, 2)}`)
  return `${parts.join('，')}。`
})

function distanceOf(price: number): string {
  if (!(latestPrice.value > 0)) {
    return '--'
  }
  const pct = ((price - latestPrice.value) / latestPrice.value) * 100
  const sign = pct >= 0 ? '+' : ''
  return `${sign}${fmtNum(pct, 2)}%`
}

function fibText(fib: number | undefined): string {
  return fib == null ? '' : `斐波那契 ${fib.toFixed(3)}`
}
</script>

<template>
  <div class="level-tab">
    <div v-if="loading && !bars.length" class="state">K 线加载中…</div>

    <div v-else-if="error && !bars.length" class="state state--error">
      加载失败：{{ error }}
      <button type="button" class="state__retry" @click="reload()">重试</button>
    </div>

    <div v-else-if="bars.length < 30" class="state">
      K 线不足 30 根，无法识别支撑阻力（当前 {{ bars.length }} 根）。
    </div>

    <div v-else-if="!levels.support.length && !levels.resistance.length" class="state">
      未找到现价 ±25% 内的有效支撑/阻力位（波段高低点不足，斐波那契补位也未落在区间内）。
    </div>

    <template v-else>
      <p v-if="error" class="hint hint--error">{{ error }}</p>

      <div class="level-grid">
        <section class="card">
          <header class="card__head">
            <h2 class="card__title">支撑与阻力</h2>
            <span class="card__extra">按最新收盘价计算</span>
          </header>

          <div class="ladder">
            <div
              v-for="level in resistanceTopDown"
              :key="`r-${level.price}`"
              class="ladder__row ladder__row--resistance"
            >
              <span class="ladder__side">阻力</span>
              <span class="ladder__price">{{ fmtNum(level.price, 2) }}</span>
              <span class="ladder__meta">
                触及 {{ level.strength }} 次 · {{ distanceOf(level.price) }}
                <template v-if="fibText(level.fib)"> · {{ fibText(level.fib) }}</template>
              </span>
            </div>

            <div class="ladder__row ladder__row--price">
              <span class="ladder__side">现价</span>
              <span class="ladder__price">{{ fmtNum(latestPrice, 2) }}</span>
              <span class="ladder__meta">最新收盘价</span>
            </div>

            <div
              v-for="level in levels.support"
              :key="`s-${level.price}`"
              class="ladder__row ladder__row--support"
            >
              <span class="ladder__side">支撑</span>
              <span class="ladder__price">{{ fmtNum(level.price, 2) }}</span>
              <span class="ladder__meta">
                触及 {{ level.strength }} 次 · {{ distanceOf(level.price) }}
                <template v-if="fibText(level.fib)"> · {{ fibText(level.fib) }}</template>
              </span>
            </div>
          </div>
        </section>

        <section class="card">
          <header class="card__head">
            <h2 class="card__title">距离与盈亏比</h2>
          </header>
          <p class="conclusion">{{ conclusion }}</p>

          <dl class="kv">
            <div class="kv__row">
              <dt>最近支撑</dt>
              <dd>{{ nearestSupport ? fmtNum(nearestSupport.price, 2) : '--' }}</dd>
            </div>
            <div class="kv__row">
              <dt>下方空间</dt>
              <dd>{{ nearestSupport && supportDistancePct != null ? `${fmtNum(supportDistancePct, 2)}%` : '--' }}</dd>
            </div>
            <div class="kv__row">
              <dt>最近阻力</dt>
              <dd>{{ nearestResistance ? fmtNum(nearestResistance.price, 2) : '--' }}</dd>
            </div>
            <div class="kv__row">
              <dt>上方空间</dt>
              <dd>
                {{ nearestResistance && resistanceDistancePct != null ? `${fmtNum(resistanceDistancePct, 2)}%` : '--' }}
              </dd>
            </div>
            <div class="kv__row">
              <dt>盈亏比（上方/下方）</dt>
              <dd>{{ riskReward == null ? '—' : fmtNum(riskReward, 2) }}</dd>
            </div>
          </dl>

          <p class="card__note">
            波段高低点用左右各 3 根窗口的极值判定，价格相差 0.5% 内聚成一档，只保留现价 ±25% 内的前 3 条；
            不足 3 条时用斐波那契回撤位补充（强度记 0，所以显示「触及 0 次」）。
          </p>
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.level-tab {
  display: flex;
  flex-direction: column;
  gap: var(--s-4);
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

.state__retry {
  margin-left: var(--s-2);
  font: inherit;
  font-size: var(--t-sm);
  color: var(--c-brand);
  background: none;
  border: 0;
  cursor: pointer;
  text-decoration: underline;
}

.hint {
  margin: 0;
  font-size: var(--t-xs);
}

.hint--error {
  color: var(--stock-up);
}

.level-grid {
  display: grid;
  grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
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

.card__note {
  margin: 0;
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.ladder {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.ladder__row {
  display: grid;
  grid-template-columns: 40px 88px minmax(0, 1fr);
  align-items: baseline;
  gap: var(--s-2);
  padding: 6px var(--s-3);
  font-size: var(--t-sm);
  font-variant-numeric: tabular-nums;
  border-radius: var(--r-sm);
  background: var(--c-surface-sunken);
}

.ladder__row--resistance {
  background: color-mix(in srgb, var(--stock-up) 7%, transparent);
}

.ladder__row--support {
  background: color-mix(in srgb, var(--stock-down) 7%, transparent);
}

.ladder__row--price {
  background: var(--c-brand-soft);
}

.ladder__side {
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.ladder__row--price .ladder__side,
.ladder__row--price .ladder__price {
  color: var(--c-brand);
  font-weight: 700;
}

.ladder__price {
  font-weight: 600;
}

.ladder__meta {
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.conclusion {
  margin: 0;
  font-size: var(--t-sm);
  line-height: 1.7;
  color: var(--c-text-secondary);
}

.kv {
  display: flex;
  flex-direction: column;
  gap: var(--s-2);
  margin: 0;
}

.kv__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-3);
}

.kv__row dt {
  font-size: var(--t-sm);
  color: var(--c-text-muted);
}

.kv__row dd {
  margin: 0;
  font-size: var(--t-sm);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

@media (max-width: 720px) {
  .level-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .card {
    padding: var(--s-4);
  }

  .ladder__row {
    grid-template-columns: 36px 72px minmax(0, 1fr);
  }
}
</style>
