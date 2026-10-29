<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { fetchKline } from '@/api/stock'
import {
  DEFAULT_HORIZON,
  DEFAULT_MIN_SAMPLE,
  SIGNAL_HISTORY_DISCLAIMER,
  clearSignalEvents,
  computeWinRates,
  listSignalEvents,
  sampleWarning,
  summarizeWinRates,
  type SignalEvent,
  type WinRateResult
} from '@/utils/signalHistory'
import { fmtNum } from '@/utils/stockFormat'

/**
 * 历史信号胜率。
 *
 * <p>统计的是**用户真的看到过**的信号：只有在详情页打开过某只票、那一轮刷新算出了标记，
 * 才会落一条记录。所以样本天然稀疏，还带选择偏差——人多看的票记录就多。
 * 界面上必须把样本量（n）摆在胜率旁边，不能只给一个百分比让人误当策略回测。</p>
 *
 * <p>与「信号回测」Tab 的区别：回测是拿固定规则跑历史全量数据，这里只有实际发生过的记录。
 * 两处口径不同，UI 上分开呈现，不放同一个表里。</p>
 */

const props = defineProps<{ code?: string }>()

const loading = ref(false)
const error = ref('')
const events = ref<SignalEvent[]>([])
const result = ref<WinRateResult | null>(null)
/** 参与计算的代码数：日 K 是逐只拉的，代码太多要限流，这里如实告诉用户覆盖了几只 */
const coveredCodes = ref(0)

const horizon = DEFAULT_HORIZON

const summary = computed(() => (result.value ? summarizeWinRates(result.value) : ''))
const warning = computed(() => (result.value ? sampleWarning(result.value, DEFAULT_MIN_SAMPLE) : ''))

/**
 * 拉取涉及的日 K。
 *
 * <p>只取最近 120 根：胜率最多看信号后第 3 个交易日，120 根足够覆盖近半年的记录；
 * 取全量既慢又没必要，而记录上限是 2000 条，真要全量得几百个请求。</p>
 */
async function loadBars(codes: string[]): Promise<Record<string, { date: string; close: number }[]>> {
  const out: Record<string, { date: string; close: number }[]> = {}
  await Promise.all(
    codes.map(async (code) => {
      try {
        const bars = await fetchKline(code, 'day', 120)
        out[code] = bars.map((bar) => ({ date: String(bar.date), close: Number(bar.close) }))
      } catch {
        // 单只拉不到就当没有后续 K 线处理：那条记录会进 pending，不会误判成失败
      }
    })
  )
  return out
}

async function refresh(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const all = listSignalEvents()
    const scoped = props.code ? all.filter((event) => event.code === props.code) : all
    events.value = scoped
    if (scoped.length === 0) {
      result.value = null
      coveredCodes.value = 0
      return
    }
    // 代码去重后限量：超过 40 只就只统计前 40 只，避免一屏胜率打几十个请求
    const codes = [...new Set(scoped.map((event) => event.code))].slice(0, 40)
    coveredCodes.value = codes.length
    const bars = await loadBars(codes)
    result.value = computeWinRates(scoped, bars, { horizon: DEFAULT_HORIZON, minSample: DEFAULT_MIN_SAMPLE })
  } catch (e: unknown) {
    error.value = (e as { message?: string })?.message || '统计失败'
  } finally {
    loading.value = false
  }
}

function onClear(): void {
  if (typeof window !== 'undefined' && !window.confirm('确定清空全部信号记录？胜率将无法恢复。')) {
    return
  }
  clearSignalEvents()
  void refresh()
}

function returnTone(value: number | null): string {
  if (value === null || value === 0) return ''
  return value > 0 ? 'is-up' : 'is-down'
}

onMounted(() => {
  void refresh()
})
</script>

<template>
  <section class="history-tab">
    <header class="history-tab__head">
      <div>
        <h2 class="history-tab__title">历史信号胜率</h2>
        <p class="history-tab__desc">
          统计你在行情页实际看到过的信号，按「信号后第 {{ horizon }} 个交易日收盘」估算收益。
        </p>
      </div>
      <div class="history-tab__actions">
        <button type="button" class="btn btn--ghost btn--sm" :disabled="loading" @click="refresh">刷新</button>
        <button
          type="button"
          class="btn btn--ghost btn--sm"
          :disabled="loading || events.length === 0"
          @click="onClear"
        >
          清空记录
        </button>
      </div>
    </header>

    <p v-if="error" class="history-tab__error">{{ error }}</p>

    <p v-if="loading" class="history-tab__hint">统计中…</p>

    <template v-else>
      <p v-if="events.length === 0" class="empty-state">
        还没有记录。打开个股详情页停留一会儿，分时图算出信号后会自动记下来。
      </p>

      <template v-else>
        <p class="history-tab__summary">{{ summary }}</p>

        <div v-if="warning" class="history-tab__warn">{{ warning }}</div>

        <p v-if="result && result.pendingCount > 0" class="history-tab__hint">
          {{ result.pendingCount }} 条记录还没有足够的后续日 K，暂不计入胜率。
        </p>
        <p v-if="coveredCodes > 0" class="history-tab__hint">
          覆盖 {{ coveredCodes }} 只股票，共 {{ events.length }} 条记录。
        </p>

        <div v-if="result && result.buckets.length > 0" class="history-tab__table-wrap">
          <table class="history-tab__table">
            <thead>
              <tr>
                <th scope="col">信号</th>
                <th scope="col">来源</th>
                <th scope="col" class="num">样本 n</th>
                <th scope="col" class="num">胜 / 负</th>
                <th scope="col" class="num">胜率</th>
                <th scope="col" class="num">平均收益</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="bucket in result.buckets" :key="`${bucket.source}-${bucket.label}`">
                <td>{{ bucket.label }}</td>
                <td class="source">{{ bucket.source === 't0' ? '分时' : bucket.source === 'ma-cross' ? '均线' : '价格' }}</td>
                <td class="num">{{ bucket.sample }}</td>
                <td class="num">{{ bucket.wins }} / {{ bucket.sample - bucket.wins }}</td>
                <td class="num">
                  {{ bucket.winRate === null ? '--' : `${fmtNum(bucket.winRate, 1)}%` }}
                  <span
                    v-if="bucket.winRate !== null && bucket.sample < DEFAULT_MIN_SAMPLE"
                    class="thin"
                    title="样本太少，不足以下结论"
                  >样本少</span>
                </td>
                <td class="num" :class="returnTone(bucket.avgReturn)">
                  {{ bucket.avgReturn === null ? '--' : `${fmtNum(bucket.avgReturn, 2)}%` }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <p class="history-tab__disclaimer">{{ SIGNAL_HISTORY_DISCLAIMER }}。</p>
    </template>
  </section>
</template>

<style scoped>
.history-tab__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s-4);
  flex-wrap: wrap;
  margin-bottom: var(--s-4);
}

.history-tab__title {
  margin: 0;
  font-size: var(--t-base);
  font-weight: 600;
  color: var(--c-text);
}

.history-tab__desc {
  margin: var(--s-1) 0 0;
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.history-tab__actions {
  display: flex;
  gap: var(--s-2);
}

.btn--sm {
  padding: 5px 12px;
  font-size: var(--t-xs);
}

.history-tab__summary {
  margin: 0 0 var(--s-3);
  padding: var(--s-3);
  border-radius: var(--r);
  background: var(--c-surface-sunken);
  font-size: var(--t-sm);
  color: var(--c-text);
}

.history-tab__warn {
  margin-bottom: var(--s-3);
  padding: var(--s-3);
  border-left: 3px solid #e8b339;
  border-radius: var(--r-sm);
  background: color-mix(in srgb, #e8b339 12%, transparent);
  font-size: var(--t-xs);
  color: #a1710d;
}

.history-tab__hint {
  margin: 0 0 var(--s-2);
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.history-tab__error {
  margin: 0 0 var(--s-3);
  font-size: var(--t-sm);
  color: var(--stock-up);
}

.history-tab__table-wrap {
  overflow-x: auto;
  border: 1px solid var(--c-border);
  border-radius: var(--r);
}

.history-tab__table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--t-sm);
  font-variant-numeric: tabular-nums;
}

.history-tab__table th,
.history-tab__table td {
  padding: var(--s-2) var(--s-3);
  text-align: left;
  white-space: nowrap;
  border-bottom: 1px solid var(--c-border);
}

.history-tab__table th {
  background: var(--c-surface-sunken);
  font-size: var(--t-xs);
  font-weight: 600;
  color: var(--c-text-muted);
}

.history-tab__table tr:last-child td {
  border-bottom: 0;
}

.history-tab__table .num {
  text-align: right;
}

.history-tab__table .source {
  color: var(--c-text-muted);
}

.history-tab__table .is-up {
  color: var(--stock-up);
}

.history-tab__table .is-down {
  color: var(--stock-down);
}

.history-tab__table .thin {
  margin-left: 6px;
  padding: 0 5px;
  border-radius: var(--r-full);
  background: color-mix(in srgb, #e8b339 18%, transparent);
  color: #a1710d;
  font-size: 10px;
}

.history-tab__disclaimer {
  margin: var(--s-4) 0 0;
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.empty-state {
  padding: var(--s-6);
  border: 1px dashed var(--c-border);
  border-radius: var(--r);
  text-align: center;
  font-size: var(--t-sm);
  color: var(--c-text-muted);
}
</style>
