<script setup lang="ts">
import { computed } from 'vue'
import {
  analyzeTech,
  type KdjStatus
} from '@/utils/techIndicators'
import type { CrossSignal } from '@/utils/stockIndicators'
import { fmtNum, toneClass } from '@/utils/stockFormat'
import { useKlineBars } from './useKlineBars'

/**
 * 技术指标 Tab。
 *
 * <p>全部计算走 `@/utils/techIndicators`：这里再做一遍 MACD/KDJ，
 * 迟早会和桌面版、回测里用的口径分叉。本组件只负责把结果翻译成中文结论。</p>
 */

const props = defineProps<{ code: string }>()

const { bars, loading, error, reload } = useKlineBars(computed(() => props.code), 120)

const tech = computed(() => analyzeTech(bars.value))

const ALIGNMENT_TEXT: Record<string, string> = {
  bullish: '多头排列',
  bearish: '空头排列',
  tangled: '均线交织'
}

const ALIGNMENT_HINT: Record<string, string> = {
  bullish: '短周期均线全部位于长周期之上，趋势结构偏多。',
  bearish: '短周期均线全部位于长周期之下，趋势结构偏空。',
  tangled: '短期与长期均线互相穿插，方向不明，别把震荡当成趋势。'
}

const alignmentText = computed(() => {
  const t = tech.value
  return t?.alignment ? ALIGNMENT_TEXT[t.alignment] : '均线不足'
})

const alignmentHint = computed(() => {
  const t = tech.value
  if (!t) {
    return ''
  }
  return t.alignment ? ALIGNMENT_HINT[t.alignment] : '均线数量不足 3 条，只能等 K 线继续积累。'
})

const macdCrossText = computed(() => crossText(tech.value?.macdCross ?? null))
const kdjCrossText = computed(() => crossText(tech.value?.kdjCross ?? null))

const macdHint = computed(() => {
  const t = tech.value
  if (!t) {
    return ''
  }
  if (t.macd.macd > 0) {
    return '柱值在零轴上方，多头动能占优。'
  }
  if (t.macd.macd < 0) {
    return '柱值在零轴下方，空头动能占优。'
  }
  return '柱值贴近零轴，多空力量接近平衡。'
})

function crossText(cross: CrossSignal | null): string {
  if (!cross) {
    return '近期无交叉'
  }
  // barsAgo 是「距最新一根多少根」，把它说成日期反而要靠每根 K 线的间隔去推，容易错
  return `${cross.direction === 'golden' ? '金叉' : '死叉'} · ${cross.barsAgo} 根前`
}

function statusText(status: KdjStatus | null): string {
  if (status === 'overbought') {
    return '超买'
  }
  if (status === 'oversold') {
    return '超卖'
  }
  if (status === 'normal') {
    return '中性'
  }
  return '--'
}

function statusClass(status: KdjStatus | null): string {
  if (status === 'overbought') {
    return 'tag--warn'
  }
  if (status === 'oversold') {
    return 'tag--cold'
  }
  return ''
}
</script>

<template>
  <div class="tech-tab">
    <p class="period-note">
      当前只统计日 K：MA5 = 5 日收盘均线，MACD(12,26,9)、KDJ(9,3,3)、RSI14、WR14 均为默认参数。
      将来若支持周/月 K，均线口径要随之改为「周/月均线」，否则数字会误导。
    </p>

    <p v-if="error && bars.length" class="state state--inline">{{ error }}</p>

    <div v-if="loading && !bars.length" class="state">K 线加载中…</div>

    <div v-else-if="error && !bars.length" class="state state--error">
      加载失败：{{ error }}
      <button type="button" class="state__retry" @click="reload()">重试</button>
    </div>

    <div v-else-if="!tech" class="state">
      K 线数据不足 26 根，无法计算指标（当前 {{ bars.length }} 根）。
    </div>

    <div v-else class="grid">
      <article class="card card--ma">
        <header class="card__head">
          <h2 class="card__title">均线趋势</h2>
          <span class="tag">{{ alignmentText }}</span>
        </header>
        <dl class="ma-grid">
          <div v-for="ma in tech.mas" :key="ma.period" class="ma-item">
            <dt>MA{{ ma.period }}</dt>
            <dd>{{ fmtNum(ma.value, 2) }}</dd>
          </div>
        </dl>
        <p class="card__hint">{{ alignmentHint }}</p>
        <p class="card__note">有效 K 线 {{ tech.bars }} 根。</p>
      </article>

      <article class="card">
        <header class="card__head">
          <h2 class="card__title">MACD</h2>
          <span class="tag">{{ macdCrossText }}</span>
        </header>
        <dl class="kv">
          <div class="kv__row">
            <dt>DIF</dt>
            <dd>{{ fmtNum(tech.macd.dif, 3) }}</dd>
          </div>
          <div class="kv__row">
            <dt>DEA</dt>
            <dd>{{ fmtNum(tech.macd.dea, 3) }}</dd>
          </div>
          <div class="kv__row">
            <dt>柱值</dt>
            <dd :class="toneClass(tech.macd.macd)">{{ fmtNum(tech.macd.macd, 3) }}</dd>
          </div>
        </dl>
        <p class="card__hint">{{ macdHint }}</p>
        <p class="card__note">交叉只在最近 2 根内判定，「无交叉」不等于没有历史交叉。</p>
      </article>

      <article class="card">
        <header class="card__head">
          <h2 class="card__title">KDJ</h2>
          <span class="tag" :class="statusClass(tech.kdjStatus)">{{ statusText(tech.kdjStatus) }}</span>
        </header>
        <dl class="kv">
          <div class="kv__row">
            <dt>K</dt>
            <dd>{{ fmtNum(tech.kdj.k, 2) }}</dd>
          </div>
          <div class="kv__row">
            <dt>D</dt>
            <dd>{{ fmtNum(tech.kdj.d, 2) }}</dd>
          </div>
          <div class="kv__row">
            <dt>J</dt>
            <dd>{{ fmtNum(tech.kdj.j, 2) }}</dd>
          </div>
        </dl>
        <p class="card__hint">{{ kdjCrossText }}</p>
        <p class="card__note">K/D &gt; 80 或 J &gt; 100 为超买；K/D &lt; 20 或 J &lt; 0 为超卖。</p>
      </article>

      <article class="card">
        <header class="card__head">
          <h2 class="card__title">WR（威廉指标）</h2>
          <span class="tag" :class="statusClass(tech.wrStatus)">{{ statusText(tech.wrStatus) }}</span>
        </header>
        <p class="value">{{ tech.wr == null ? '--' : fmtNum(tech.wr, 2) }}</p>
        <p class="card__note">
          WR 值域 -100 ~ 0：≥ -20 超买，≤ -80 超卖。注意方向与其他指标相反，越接近 0 越超买。
        </p>
      </article>

      <article class="card">
        <header class="card__head">
          <h2 class="card__title">RSI14</h2>
          <span class="tag" :class="statusClass(tech.rsiStatus)">{{ statusText(tech.rsiStatus) }}</span>
        </header>
        <p class="value" :class="toneClass(tech.rsi == null ? null : tech.rsi - 50)">
          {{ tech.rsi == null ? '--' : fmtNum(tech.rsi, 2) }}
        </p>
        <p class="card__note">&gt; 70 为超买，&lt; 30 为超卖，30–70 之间为中性区间。</p>
      </article>
    </div>
  </div>
</template>

<style scoped>
.tech-tab {
  display: flex;
  flex-direction: column;
  gap: var(--s-4);
}

.period-note {
  margin: 0;
  padding: var(--s-3) var(--s-4);
  font-size: var(--t-xs);
  color: var(--c-text-muted);
  background: var(--c-surface-sunken);
  border-radius: var(--r-md);
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

.state--inline {
  padding: var(--s-3) var(--s-4);
  text-align: left;
  font-size: var(--t-xs);
  color: var(--stock-up);
  background: color-mix(in srgb, var(--stock-up) 6%, transparent);
  border-color: color-mix(in srgb, var(--stock-up) 30%, transparent);
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

.grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s-4);
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

.card--ma {
  grid-column: 1 / -1;
}

.card__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--s-2);
}

.card__title {
  margin: 0;
  font-size: var(--t-base);
  font-weight: 600;
  color: var(--c-text);
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

.tag {
  padding: 1px 8px;
  font-size: var(--t-xs);
  font-weight: 600;
  color: var(--c-text-secondary);
  background: var(--c-surface-sunken);
  border-radius: var(--r-full);
}

.tag--warn {
  color: var(--c-accent);
  background: color-mix(in srgb, var(--c-accent) 12%, transparent);
}

.tag--cold {
  color: var(--c-brand);
  background: color-mix(in srgb, var(--c-brand) 12%, transparent);
}

.ma-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--s-3);
  margin: 0;
}

.ma-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--s-3);
  background: var(--c-surface-sunken);
  border-radius: var(--r-sm);
}

.ma-item dt {
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.ma-item dd {
  margin: 0;
  font-size: var(--t-md);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
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

.value {
  margin: 0;
  font-size: var(--t-2xl);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
}

@media (max-width: 720px) {
  .grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .ma-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .card {
    padding: var(--s-4);
  }
}
</style>
