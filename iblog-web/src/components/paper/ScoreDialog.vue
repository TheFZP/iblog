<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { fetchPaperStockScore, type PaperStockScore } from '@/api/paper'
import { searchStock, type SearchResult } from '@/api/stock'

/**
 * 「选股评分」弹窗：按当前模拟盘账户的规则给任意一只股票打分。
 *
 * <p>搜索复用行情页的 searchStock（腾讯 smartbox，代码 / 名称 / 拼音首字母都能查）。
 * 评分只是答疑，不写候选、不下单；硬性过滤不参与，所以被规则过滤掉的票也能看到分数。</p>
 */
const props = defineProps<{
  show: boolean
  /** 当前模拟盘账户 id：规则、情绪、可用资金都按这个账户算 */
  accountId?: number | null
  /** 账户名，仅用于弹窗里说明「按哪套规则打分」 */
  accountName?: string
}>()

const emit = defineEmits<{ close: [] }>()

const keyword = ref('')
const results = ref<SearchResult[]>([])
const searching = ref(false)
const searchError = ref('')
const dropdownOpen = ref(false)

const selected = ref<{ code: string; name: string } | null>(null)
const scoring = ref(false)
const scoreError = ref('')
const result = ref<PaperStockScore | null>(null)

let searchTimer: ReturnType<typeof setTimeout> | undefined
let searchSeq = 0

const passed = computed(() => {
  const value = result.value
  return value != null && value.score >= value.minScore
})

function clearSearchTimer() {
  if (searchTimer) {
    clearTimeout(searchTimer)
    searchTimer = undefined
  }
}

function onKeywordInput() {
  clearSearchTimer()
  const kw = keyword.value.trim()
  if (!kw) {
    results.value = []
    searching.value = false
    searchError.value = ''
    dropdownOpen.value = false
    return
  }
  searching.value = true
  searchTimer = setTimeout(() => {
    void runSearch(kw)
  }, 300)
}

async function runSearch(kw: string) {
  const seq = ++searchSeq
  try {
    const list = await searchStock(kw)
    if (seq !== searchSeq) return
    results.value = (list ?? []).slice(0, 10)
    dropdownOpen.value = results.value.length > 0
    searchError.value = ''
  } catch (err) {
    if (seq !== searchSeq) return
    results.value = []
    dropdownOpen.value = false
    searchError.value = err instanceof Error ? err.message : '搜索失败，请稍后重试'
  } finally {
    if (seq === searchSeq) searching.value = false
  }
}

function pick(item: SearchResult) {
  selected.value = { code: item.code, name: item.name }
  keyword.value = ''
  results.value = []
  dropdownOpen.value = false
  searchError.value = ''
  scoreError.value = ''
  result.value = null
}

function clearStock() {
  selected.value = null
  result.value = null
  scoreError.value = ''
}

async function runScore() {
  if (!selected.value || scoring.value) return
  scoring.value = true
  scoreError.value = ''
  result.value = null
  try {
    result.value = await fetchPaperStockScore(
      selected.value.code,
      selected.value.name,
      props.accountId ?? undefined
    )
  } catch (err) {
    scoreError.value = err instanceof Error ? err.message : '评分失败，请稍后重试'
  } finally {
    scoring.value = false
  }
}

function reset() {
  keyword.value = ''
  results.value = []
  searching.value = false
  searchError.value = ''
  dropdownOpen.value = false
  selected.value = null
  scoring.value = false
  scoreError.value = ''
  result.value = null
  clearSearchTimer()
  searchSeq++
}

watch(
  () => props.show,
  (visible) => {
    if (visible) reset()
    else clearSearchTimer()
  },
  { immediate: true }
)

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && props.show) {
    emit('close')
  }
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onUnmounted(() => {
  document.removeEventListener('keydown', onKeydown)
  clearSearchTimer()
})
</script>

<template>
  <div v-if="show" class="stock-modal" @click.self="emit('close')">
    <div class="stock-modal__box score-box" role="dialog" aria-modal="true" aria-labelledby="score-dialog-title">
      <header class="stock-modal__head">
        <h2 id="score-dialog-title" class="stock-modal__title">选股评分</h2>
        <button type="button" class="stock-modal__close" aria-label="关闭" @click="emit('close')">✕</button>
      </header>

      <p class="score-note">
        按{{ accountName ? `「${accountName}」` : '当前模拟盘' }}的规则打分，只计算分数，不写候选、不下单。
        热度类因子（热榜排名）不参与；硬性过滤不生效，所以被规则拦下的票也能看到分数。
      </p>

      <div class="score-field">
        <span class="score-field__label">股票</span>
        <div class="score-field__main">
          <template v-if="!selected">
            <div class="score-search">
              <input
                v-model="keyword"
                class="score-input"
                type="search"
                placeholder="输入代码 / 名称 / 拼音首字母，如 600519 / 茅台 / gzmt"
                autocomplete="off"
                spellcheck="false"
                aria-label="搜索股票"
                @input="onKeywordInput"
                @focus="dropdownOpen = results.length > 0"
                @keydown.enter.prevent="results.length === 1 && pick(results[0])"
              />
              <span v-if="searching" class="score-search__hint">搜索中…</span>
              <ul v-if="dropdownOpen && results.length" class="score-dropdown">
                <li v-for="item in results" :key="item.code">
                  <button type="button" class="score-dropdown__item" @click="pick(item)">
                    <span class="score-dropdown__name">{{ item.name }}</span>
                    <span class="score-dropdown__code">{{ item.displayCode }}</span>
                  </button>
                </li>
              </ul>
            </div>
            <p v-if="searchError" class="score-error-text">{{ searchError }}</p>
          </template>

          <div v-else class="score-picked">
            <span class="score-picked__name">{{ selected.name }}</span>
            <span class="score-picked__code">{{ selected.code }}</span>
            <button type="button" class="score-link" @click="clearStock">更换</button>
          </div>
        </div>
      </div>

      <div class="score-actions">
        <button
          type="button"
          class="btn"
          :disabled="!selected || scoring"
          @click="runScore"
        >
          {{ scoring ? '计算中…' : '开始评分' }}
        </button>
      </div>

      <p v-if="scoreError" class="score-error" role="alert">{{ scoreError }}</p>

      <section v-if="result" class="score-result" aria-live="polite">
        <div class="score-result__head">
          <span class="score-result__label">{{ result.name }}（{{ result.code }}）</span>
          <span class="score-result__value" :class="passed ? 'is-pass' : 'is-fail'">{{ result.score }}</span>
          <span class="chip" :class="passed ? 'is-pass' : 'is-fail'">
            {{ passed ? '达到入选门槛' : '未达入选门槛' }}
          </span>
          <span class="score-result__meta">门槛 {{ result.minScore }} 分</span>
        </div>
        <p v-if="result.reason" class="score-result__reason">{{ result.reason }}</p>
        <p v-else class="score-result__reason is-muted">当前规则下没有命中任何加分项</p>
        <p class="score-result__foot">
          结果基于当前日 K、实时行情和缓存的新闻分；盘中最后一根日 K 还在变化，分数会随之浮动。
        </p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.score-box {
  width: min(600px, 100%);
}

.score-note {
  margin: 0 0 var(--s-4);
  font-size: var(--t-xs);
  line-height: 1.7;
  color: var(--c-text-muted);
}

.score-field {
  display: flex;
  align-items: flex-start;
  gap: var(--s-3);
  margin-bottom: var(--s-3);
}

.score-field__label {
  flex: none;
  width: 44px;
  padding-top: 11px;
  font-size: var(--t-sm);
  color: var(--c-text-muted);
}

.score-field__main {
  flex: 1;
  min-width: 0;
}

.score-search {
  position: relative;
}

.score-input {
  width: 100%;
  height: 42px;
  padding: 0 var(--s-3);
  border: 1px solid var(--c-border-strong);
  border-radius: var(--r);
  background: var(--c-surface);
  color: var(--c-text);
  font-size: var(--t-base);
  font-family: inherit;
  transition: border-color 0.15s var(--ease), box-shadow 0.15s var(--ease);
}

.score-input:focus {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: 0 0 0 3px var(--c-brand-ring);
}

.score-search__hint {
  position: absolute;
  right: 10px;
  top: 50%;
  transform: translateY(-50%);
  font-size: var(--t-xs);
  color: var(--c-text-faint);
  pointer-events: none;
}

.score-dropdown {
  position: absolute;
  z-index: 30;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  max-height: 264px;
  overflow-y: auto;
  margin: 0;
  padding: var(--s-1);
  list-style: none;
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface);
  box-shadow: var(--e-lg);
}

.score-dropdown__item {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-3);
  width: 100%;
  padding: 9px var(--s-3);
  border: 0;
  border-radius: var(--r-sm);
  background: none;
  color: var(--c-text-secondary);
  font-size: var(--t-sm);
  font-family: inherit;
  text-align: left;
  cursor: pointer;
}

.score-dropdown__item:hover {
  background: var(--c-brand-soft);
  color: var(--c-brand);
}

.score-dropdown__name {
  font-weight: 560;
  color: var(--c-text);
}

.score-dropdown__item:hover .score-dropdown__name {
  color: var(--c-brand);
}

.score-dropdown__code {
  font-family: var(--f-mono);
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.score-picked {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex-wrap: wrap;
  min-height: 42px;
}

.score-picked__name {
  font-size: var(--t-md);
  font-weight: 620;
}

.score-picked__code {
  font-family: var(--f-mono);
  font-size: var(--t-sm);
  color: var(--c-text-faint);
}

.score-link {
  padding: 0;
  border: 0;
  background: none;
  color: var(--c-brand);
  font-size: var(--t-xs);
  font-family: inherit;
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 3px;
}

.score-actions {
  display: flex;
  justify-content: flex-end;
  margin-bottom: var(--s-3);
}

.score-error-text {
  margin: var(--s-2) 0 0;
  font-size: var(--t-xs);
  color: var(--stock-up);
}

.score-error {
  margin: 0 0 var(--s-3);
  padding: var(--s-2) var(--s-3);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--stock-up) 10%, transparent);
  color: var(--stock-up);
  font-size: var(--t-sm);
}

.score-result {
  padding: var(--s-4);
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface-sunken);
}

.score-result__head {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: var(--s-2);
}

.score-result__label {
  font-size: var(--t-sm);
  color: var(--c-text-muted);
}

.score-result__value {
  font-family: var(--f-mono);
  font-size: var(--t-2xl);
  font-weight: 640;
  font-variant-numeric: tabular-nums;
  line-height: 1;
}

.score-result__value.is-pass {
  color: var(--stock-up);
}

.score-result__value.is-fail {
  color: var(--c-text-muted);
}

.chip.is-pass {
  background: color-mix(in srgb, var(--stock-up) 12%, transparent);
  color: var(--stock-up);
}

.chip.is-fail {
  background: var(--c-surface);
  color: var(--c-text-faint);
}

.score-result__meta {
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.score-result__reason {
  margin: var(--s-3) 0 0;
  font-size: var(--t-sm);
  line-height: 1.7;
  color: var(--c-text-secondary);
}

.score-result__reason.is-muted {
  color: var(--c-text-faint);
}

.score-result__foot {
  margin: var(--s-3) 0 0;
  padding-top: var(--s-2);
  border-top: 1px dashed var(--c-border);
  font-size: var(--t-xs);
  line-height: 1.6;
  color: var(--c-text-faint);
}

@media (max-width: 600px) {
  .score-field {
    flex-direction: column;
    gap: var(--s-2);
  }

  .score-field__label {
    width: auto;
    padding-top: 0;
  }

  .score-actions .btn {
    flex: 1;
  }
}
</style>
