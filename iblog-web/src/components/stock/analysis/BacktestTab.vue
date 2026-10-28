<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  HOLD_PERIODS,
  MIN_BARS,
  analyzeBars,
  type PeriodSummary
} from '@/utils/signalBacktest'
import { fmtNum, fmtSigned, toneClass } from '@/utils/stockFormat'
import { useKlineBars } from './useKlineBars'

/**
 * 信号回测 Tab。
 *
 * <p>回测结论只说明「这条规则历史上触发后的 N 日表现」，不等于「现在该买」：
 * 非重叠、固定持有期、未扣费滑点，这三点界面必须写清楚，否则胜率会被当成交易系统收益。</p>
 */

const props = defineProps<{ code: string }>()

const { bars, loading, error, reload } = useKlineBars(computed(() => props.code), 480)

const result = computed(() => analyzeBars(bars.value, props.code))

const selectedId = ref<string | null>(null)

const selectedRule = computed(() => {
  const rules = result.value.rules
  return rules.find((rule) => rule.id === selectedId.value) ?? rules[0] ?? null
})

const periodCards = computed(() => {
  const rule = selectedRule.value
  if (!rule) {
    return []
  }
  return HOLD_PERIODS
    // strict 下 Record<number, PeriodSummary> 用 number 下标访问；显式标注返回类型，
    // 否则 HOLD_PERIODS 的 as const 字面量类型会让下方 filter 的类型谓词无法收窄
    .map((hold): { hold: number; summary: PeriodSummary | undefined } => ({
      hold,
      summary: rule.byPeriod[hold as number]
    }))
    .filter((card): card is { hold: number; summary: PeriodSummary } => Boolean(card.summary))
})

function winRateText(summary: PeriodSummary): string {
  return summary.winRate == null ? '--' : `${fmtNum(summary.winRate, 1)}%`
}

function retText(value: number | null): string {
  return value == null ? '--' : `${fmtSigned(value, 2)}%`
}

function lastHitText(rule: { lastHitDate: string | null; barsAgo: number | null; evaluated: boolean }): string {
  if (rule.lastHitDate) {
    const ago = rule.barsAgo != null ? ` · ${rule.barsAgo} 根前` : ''
    return `${rule.lastHitDate}${ago}`
  }
  // evaluated=false 表示这根 K 线上该规则根本没参与判定（指标预热不足），不能说成「未触发」
  return rule.evaluated ? '未触发' : '未参与'
}

function selectRule(id: string) {
  selectedId.value = id
}
</script>

<template>
  <div class="backtest-tab">
    <div v-if="loading && !bars.length" class="state">K 线加载中…</div>

    <div v-else-if="error && !bars.length" class="state state--error">
      加载失败：{{ error }}
      <button type="button" class="state__retry" @click="reload()">重试</button>
    </div>

    <div v-else-if="!result.enough" class="state">
      K 线不足 {{ MIN_BARS }} 根，样本不足，无法回测（当前 {{ result.bars }} 根）。
    </div>

    <template v-else>
      <p v-if="error" class="hint hint--error">{{ error }}</p>

      <div class="summary-bar">
        <span class="summary-bar__item">K 线 {{ result.bars }} 根</span>
        <span class="summary-bar__item">{{ result.from ?? '--' }} ~ {{ result.to ?? '--' }}</span>
        <span class="summary-bar__item">规则 {{ result.rules.length }} 条</span>
        <span v-if="!result.enough" class="summary-bar__flag">样本不足，结果仅供参考</span>
      </div>

      <section class="card">
        <header class="card__head">
          <h2 class="card__title">规则命中统计</h2>
          <span class="card__extra">点击行查看持有期表现</span>
        </header>
        <div class="table-wrap">
          <table class="rules">
            <thead>
              <tr>
                <th scope="col">规则</th>
                <th scope="col" class="num">信号次数</th>
                <th scope="col">最近命中</th>
                <th scope="col">状态</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="rule in result.rules"
                :key="rule.id"
                :class="{ 'is-selected': selectedRule?.id === rule.id }"
                @click="selectRule(rule.id)"
              >
                <td>
                  <span class="rules__name">{{ rule.name }}</span>
                  <span class="rules__hint">{{ rule.hint }}</span>
                </td>
                <td class="num">{{ rule.signalCount }}</td>
                <td>{{ lastHitText(rule) }}</td>
                <td>
                  <span v-if="rule.active" class="tag tag--active">当前触发</span>
                  <span v-else class="tag">未触发</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="card__note">
          共 {{ result.rules.length }} 条日线规则；「当前触发」指最新一根 K 线上满足条件，不代表次日一定上涨。
        </p>
      </section>

      <section v-if="selectedRule" class="card">
        <header class="card__head">
          <h2 class="card__title">持有期表现 · {{ selectedRule.name }}</h2>
          <span class="card__extra">{{ selectedRule.hint }}</span>
        </header>

        <div class="periods">
          <article v-for="card in periodCards" :key="card.hold" class="period">
            <header class="period__head">
              <span class="period__title">持有 {{ card.hold }} 日</span>
              <span class="period__trades">{{ card.summary.trades }} 笔</span>
            </header>
            <div class="period__win">
              <span class="period__label">胜率</span>
              <span class="period__value" :class="toneClass(card.summary.winRate == null ? null : card.summary.winRate - 50)">
                {{ winRateText(card.summary) }}
              </span>
            </div>
            <dl class="kv">
              <div class="kv__row">
                <dt>期望收益</dt>
                <dd :class="toneClass(card.summary.expectancy)">{{ retText(card.summary.expectancy) }}</dd>
              </div>
              <div class="kv__row">
                <dt>平均收益</dt>
                <dd :class="toneClass(card.summary.avgRet)">{{ retText(card.summary.avgRet) }}</dd>
              </div>
              <div class="kv__row">
                <dt>最佳 / 最差</dt>
                <dd>
                  <span :class="toneClass(card.summary.best)">{{ retText(card.summary.best) }}</span>
                  <span class="slash"> / </span>
                  <span :class="toneClass(card.summary.worst)">{{ retText(card.summary.worst) }}</span>
                </dd>
              </div>
              <div class="kv__row">
                <dt>最大回撤</dt>
                <dd>{{ card.summary.maxDrawdown == null ? '--' : `${fmtNum(card.summary.maxDrawdown, 2)}%` }}</dd>
              </div>
              <div class="kv__row">
                <dt>有效 / 平盘</dt>
                <dd>{{ card.summary.decided }} / {{ card.summary.flats }}</dd>
              </div>
            </dl>
          </article>
        </div>

        <p class="card__note">
          信号日收盘价买入，持有 N 个交易日后收盘价卖出，非重叠交易（持仓期内不开新仓）；
          平盘不计入胜率分母；未扣手续费与滑点。
        </p>
      </section>
    </template>
  </div>
</template>

<style scoped>
.backtest-tab {
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

.summary-bar {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2) var(--s-4);
  padding: var(--s-3) var(--s-4);
  font-size: var(--t-xs);
  color: var(--c-text-muted);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-md);
  font-variant-numeric: tabular-nums;
}

.summary-bar__flag {
  color: var(--c-accent);
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
  flex-wrap: wrap;
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

.table-wrap {
  overflow-x: auto;
}

.rules {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--t-sm);
  font-variant-numeric: tabular-nums;
}

.rules th,
.rules td {
  padding: 10px var(--s-3);
  text-align: left;
  border-bottom: 1px solid var(--c-border);
  vertical-align: top;
}

.rules th {
  font-size: var(--t-xs);
  font-weight: 600;
  color: var(--c-text-muted);
}

.rules .num {
  text-align: right;
  white-space: nowrap;
}

.rules tbody tr {
  cursor: pointer;
}

.rules tbody tr:hover {
  background: var(--c-surface-sunken);
}

.rules tbody tr.is-selected {
  background: var(--c-brand-soft);
}

.rules__name {
  display: block;
  font-weight: 600;
  color: var(--c-text);
}

.rules__hint {
  display: block;
  margin-top: 2px;
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.tag {
  display: inline-block;
  padding: 1px 8px;
  font-size: var(--t-xs);
  color: var(--c-text-muted);
  background: var(--c-surface-sunken);
  border-radius: var(--r-full);
  white-space: nowrap;
}

.tag--active {
  color: var(--c-brand);
  background: color-mix(in srgb, var(--c-brand) 12%, transparent);
}

.periods {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--s-3);
}

.period {
  display: flex;
  flex-direction: column;
  gap: var(--s-2);
  padding: var(--s-3);
  background: var(--c-surface-sunken);
  border-radius: var(--r-md);
}

.period__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-2);
}

.period__title {
  font-size: var(--t-sm);
  font-weight: 600;
  color: var(--c-text);
}

.period__trades {
  font-size: var(--t-xs);
  color: var(--c-text-muted);
  font-variant-numeric: tabular-nums;
}

.period__win {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-2);
  padding-bottom: var(--s-2);
  border-bottom: 1px solid var(--c-border);
}

.period__label {
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.period__value {
  font-size: var(--t-lg);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.kv {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 0;
}

.kv__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-2);
}

.kv__row dt {
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.kv__row dd {
  margin: 0;
  font-size: var(--t-xs);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  text-align: right;
}

.slash {
  color: var(--c-text-faint);
}

@media (max-width: 720px) {
  .periods {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .card {
    padding: var(--s-4);
  }
}
</style>
