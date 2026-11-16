<script setup lang="ts">
/**
 * 问财选股面板（从原 /stock/iwencai 页面抽出，嵌在模拟盘的对话框里）。
 *
 * <p>表格数据来自 `/stock/ai/iwencai`：`columns` 是动态表头，`rows` 是二维数组。
 * 服务端会忽略 `page`，一次只回 `perPage` 行，所以翻页全部在前端切片。</p>
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  findColumn,
  optimizeIwencaiQuery,
  queryIwencai,
  rowCode,
  type IwencaiResult,
} from '@/api/iwencai'
import { buildPresetText, clampPage, pageCount, sliceRows } from '@/components/stock/iwencai/rows'
import { openGlobalAi } from '@/composables/useGlobalAi'

const router = useRouter()

const PAGE_SIZES = [20, 50, 100] as const
const QUICK_QUESTIONS = [
  '今日涨停',
  '连续三年ROE大于15%',
  '市盈率小于20且净利润增长大于30%',
  '成交量创60日新高',
  '近5日主力资金净流入',
] as const

// 与 api/iwencai.ts 内部的候选表头保持一致：那边没有导出，这里只用来判断「哪一列可以点」，
// 真正的代码值仍然由 rowCode() 解析。
const CODE_HEADERS = ['股票代码', '代码', '证券代码']
const NAME_HEADERS = ['股票简称', '股票名称', '名称', '证券简称']

const question = ref('')
/** 上一次真正提交的问句：重试和切换每页行数都要用它，而不是输入框里可能被改了一半的内容。 */
const lastQuestion = ref('')
const result = ref<IwencaiResult | null>(null)
const loading = ref(false)
const error = ref('')
/** 有旧结果但这次刷新失败：只标一条提示，不清空表格。 */
const stale = ref(false)
const page = ref(1)
const pageSize = ref<(typeof PAGE_SIZES)[number]>(50)
/** AI 改写请求中：避免用户连点把多次改写叠加成一串 */
const optimizing = ref(false)
/** 改写结果的一句提示（成功/失败都写这里，用空串表示无提示） */
const optimizeHint = ref('')

/** 请求序号：连续点两个快捷问句时，先发出的响应可能后到，靠它把过期响应丢掉。 */
let seq = 0

const columns = computed<string[]>(() => result.value?.columns ?? [])
const rows = computed<string[][]>(() => result.value?.rows ?? [])
const total = computed(() => rows.value.length)
/** 问财自报的 row_count 常大于本次实际返回行数（被 perPage 截断），以本地行数为准。 */
const reportedTotal = computed(() => result.value?.rowCount ?? 0)
const countMismatch = computed(() => reportedTotal.value > 0 && reportedTotal.value !== total.value)
const totalPages = computed(() => pageCount(total.value, pageSize.value))
const currentPage = computed(() => clampPage(page.value, totalPages.value))
/** 当前页的行，顺便把每行的股票代码解析一次：代码列和名称列都要拿它做链接目标。 */
const visibleRows = computed(() =>
  sliceRows(rows.value, currentPage.value, pageSize.value).map((row) => ({
    row,
    code: rowCode(columns.value, row),
  })),
)
const codeColumn = computed(() => findColumn(columns.value, CODE_HEADERS))
const nameColumn = computed(() => findColumn(columns.value, NAME_HEADERS))

async function run(text: string): Promise<void> {
  const query = text.trim()
  if (!query) {
    return
  }
  lastQuestion.value = query
  const my = ++seq
  loading.value = true
  error.value = ''
  try {
    const data = await queryIwencai(query, pageSize.value)
    if (my !== seq) {
      return
    }
    result.value = data
    page.value = 1
    stale.value = false
  } catch (e) {
    if (my !== seq) {
      return
    }
    // 失败时保留上一次成功的结果：行情类页面最忌整屏清空，用户往往只是想换个条件再看看。
    error.value = e instanceof Error ? e.message : '问财查询失败，请稍后重试'
    stale.value = result.value !== null
  } finally {
    if (my === seq) {
      loading.value = false
    }
  }
}

function submit(): void {
  void run(question.value)
}

/**
 * 让 AI 把口语改写成问财认的句式。
 *
 * <p>只改写输入框，不自动提交：改写结果用户可能不认同（比如把「低估的票」
 * 理解成某个具体市盈率），得让他看一眼再决定。改写失败保持原样并给一句提示，
 * 不能因为 AI 挂了就拦住用户查股票。</p>
 */
async function optimize(): Promise<void> {
  const raw = question.value.trim()
  if (!raw || optimizing.value) {
    return
  }
  optimizing.value = true
  optimizeHint.value = ''
  try {
    const next = await optimizeIwencaiQuery(raw)
    if (next) {
      question.value = next
      optimizeHint.value = '已改写成问财更易识别的条件，确认后再点「选股」'
    } else {
      optimizeHint.value = 'AI 优化暂不可用（可能没配置 Key），已保留原句'
    }
  } finally {
    optimizing.value = false
  }
}

function quickSearch(preset: string): void {
  question.value = preset
  void run(preset)
}

function retry(): void {
  if (lastQuestion.value) {
    void run(lastQuestion.value)
  }
}

/** 切换每页行数必须重新查询：缓存键带 perPage，且服务端按 perPage 截断，行数不同就是另一批数据。 */
function changePageSize(size: (typeof PAGE_SIZES)[number]): void {
  if (size === pageSize.value) {
    return
  }
  pageSize.value = size
  page.value = 1
  if (lastQuestion.value) {
    void run(lastQuestion.value)
  }
}

function goPage(target: number): void {
  page.value = clampPage(target, totalPages.value)
}

function cellText(row: string[], index: number): string {
  return row[index] ?? ''
}

/** 只有「代码列」和「名称列」给链接，其余列（行业、板块名等）不可点。 */
function isLinkColumn(index: number): boolean {
  return index === codeColumn.value || index === nameColumn.value
}

function openRow(code: string | null): void {
  if (code) {
    void router.push(`/stock/${code}`)
  }
}

/**
 * 把当前结果的前 30 行拼成纯文本表格，交给全局 AI 入口。
 *
 * <p>直接在当前页唤起面板，不跳路由：跳页会把用户从刚选出来的结果上踢走，
 * 回来还得重新查一遍——而问财凭证有请求次数限制，重查不便宜。
 * 面板挂在布局层，任何页面都能开，所以这里不需要路由。</p>
 *
 * <p>截 30 行是刻意的：行数再多模型也读不完，只会把提示词挤爆。</p>
 */
function askAi(): void {
  if (total.value === 0) {
    return
  }
  openGlobalAi(buildPresetText(columns.value, rows.value, 30))
}
</script>

<template>
  <div class="iw-panel">
    <p class="iw-desc">
      直接用一句话描述选股条件，例如「今日涨停且换手率大于5%」，由同花顺问财解析后返回结果。
    </p>

    <section class="surface iw-search-card">
      <form class="iw-search" @submit.prevent="submit">
        <input
          v-model="question"
          class="iw-search__input"
          type="search"
          enterkeyhint="search"
          placeholder="例如：今日涨停且换手率大于5%"
          aria-label="选股条件"
          :disabled="loading"
        />
        <button type="submit" class="btn iw-search__btn" :disabled="loading || !question.trim()">
          选股
        </button>
        <button
          type="button"
          class="btn btn--ghost iw-optimize__btn"
          :disabled="loading || optimizing || !question.trim()"
          title="让 AI 把口语条件改写成问财更易识别的写法"
          @click="optimize"
        >
          {{ optimizing ? '优化中…' : '✨ AI 优化' }}
        </button>
      </form>

      <p v-if="optimizeHint" class="iw-optimize__hint">{{ optimizeHint }}</p>

      <div class="iw-chips">
        <!-- 快捷问句在请求中也不禁用：用户连点两句很常见，让新问句抢先、旧响应靠 seq 丢弃，
             比把按钮锁住等上好几秒的体验好。 -->
        <button
          v-for="preset in QUICK_QUESTIONS"
          :key="preset"
          type="button"
          class="chip iw-chip"
          @click="quickSearch(preset)"
        >
          {{ preset }}
        </button>
      </div>

      <p v-if="loading" class="iw-loading">
        <span class="iw-spinner" aria-hidden="true" />
        正在查询问财，首次可能需要几秒…
      </p>
    </section>

    <div v-if="error" class="iw-error" role="alert">
      <span class="iw-error__text">{{ error }}</span>
      <button
        type="button"
        class="iw-error__retry"
        :disabled="loading || !lastQuestion"
        @click="retry"
      >
        重试
      </button>
    </div>

    <section v-if="result" class="surface iw-result">
      <div class="iw-result__head">
        <div class="iw-result__meta">
          <span v-if="stale" class="iw-stale">上次结果（刷新失败）</span>
          <p v-if="result.condition" class="iw-result__condition">{{ result.condition }}</p>
          <p class="iw-result__count">
            共 {{ total }} 条
            <span v-if="countMismatch" class="iw-result__mismatch">
              问财报告 {{ reportedTotal }} 条，以本次返回的 {{ total }} 行为准
            </span>
          </p>
        </div>

        <div class="iw-result__side">
          <div class="iw-sizes" role="group" aria-label="每页行数">
            <button
              v-for="size in PAGE_SIZES"
              :key="size"
              type="button"
              class="iw-size"
              :class="{ 'is-active': size === pageSize }"
              :aria-pressed="size === pageSize"
              @click="changePageSize(size)"
            >
              {{ size }}
            </button>
          </div>
          <button type="button" class="btn iw-ai-btn" :disabled="total === 0" @click="askAi">
            AI 分析这批股票
          </button>
        </div>
      </div>

      <div v-if="total === 0" class="empty-state iw-empty">
        <p class="iw-empty__title">没有符合条件的股票</p>
        <p class="iw-empty__hint">试试换个问法，或去掉过于苛刻的条件</p>
      </div>

      <template v-else>
        <div class="iw-table-wrap">
          <table class="iw-table">
            <thead>
              <tr>
                <th v-for="col in columns" :key="col" scope="col">{{ col }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(item, rowIndex) in visibleRows" :key="`${currentPage}-${rowIndex}`">
                <td
                  v-for="(_, colIndex) in columns"
                  :key="colIndex"
                  :title="cellText(item.row, colIndex)"
                >
                  <a
                    v-if="isLinkColumn(colIndex) && item.code"
                    class="iw-link"
                    :href="`/stock/${item.code}`"
                    @click.prevent="openRow(item.code)"
                  >
                    {{ cellText(item.row, colIndex) }}
                  </a>
                  <template v-else>{{ cellText(item.row, colIndex) }}</template>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="iw-pager">
          <button
            type="button"
            class="iw-pager__btn"
            :disabled="currentPage <= 1"
            @click="goPage(currentPage - 1)"
          >
            上一页
          </button>
          <span class="iw-pager__label">第 {{ currentPage }} / {{ totalPages }} 页</span>
          <button
            type="button"
            class="iw-pager__btn"
            :disabled="currentPage >= totalPages"
            @click="goPage(currentPage + 1)"
          >
            下一页
          </button>
        </div>
      </template>
    </section>

    <p v-if="!result && !loading && !error" class="empty-state iw-intro">
      输入一句选股条件，或点上面的快捷问句试试
    </p>

    <p class="iw-note">
      数据来自同花顺问财，仅供参考。问财服务端忽略分页参数，翻页由本地完成；单次最多取 100 行。
    </p>
  </div>
</template>

<style scoped>
.iw-desc {
  margin: 0 0 var(--s-3);
  color: var(--c-text-muted);
  font-size: var(--t-sm);
  line-height: 1.6;
}

.iw-search-card {
  display: flex;
  flex-direction: column;
  gap: var(--s-3);
  padding: var(--s-4);
}

.iw-search {
  display: flex;
  gap: var(--s-3);
}

.iw-search__input {
  flex: 1 1 auto;
  min-width: 0;
  height: 44px;
  padding: 0 var(--s-3);
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface);
  color: var(--c-text);
  font-size: var(--t-sm);
  font-family: inherit;
  transition: border-color 0.15s var(--ease);
}

.iw-search__input:focus {
  outline: none;
  border-color: var(--c-brand);
}

.iw-search__input:disabled {
  background: var(--c-surface-sunken);
  color: var(--c-text-faint);
}

.iw-search__btn {
  flex: 0 0 auto;
  min-height: 44px;
}

.iw-optimize__btn {
  flex: 0 0 auto;
  min-height: 44px;
  white-space: nowrap;
}

.iw-optimize__hint {
  margin: 0;
  color: var(--c-text-muted);
  font-size: var(--t-xs);
  line-height: 1.5;
}

.iw-chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2);
}

.iw-chip {
  border: 1px solid transparent;
  cursor: pointer;
  font-family: inherit;
  transition:
    border-color 0.15s var(--ease),
    color 0.15s var(--ease);
}

.iw-chip:hover:not(:disabled) {
  border-color: var(--c-border-strong);
  color: var(--c-brand);
}

.iw-loading {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  margin: 0;
  color: var(--c-text-muted);
  font-size: var(--t-xs);
}

.iw-spinner {
  width: 12px;
  height: 12px;
  border: 2px solid var(--c-border-strong);
  border-top-color: var(--c-brand);
  border-radius: var(--r-full);
  animation: iw-spin 0.8s linear infinite;
}

@keyframes iw-spin {
  to {
    transform: rotate(360deg);
  }
}

.iw-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  margin-top: var(--s-3);
  padding: var(--s-3) var(--s-4);
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface-sunken);
}

.iw-error__text {
  color: var(--c-text);
  font-size: var(--t-sm);
  line-height: 1.5;
}

.iw-error__retry {
  flex: 0 0 auto;
  min-height: 32px;
  padding: 0 var(--s-4);
  border: 1px solid var(--c-border-strong);
  border-radius: var(--r);
  background: var(--c-surface);
  color: var(--c-text);
  font-size: var(--t-xs);
  cursor: pointer;
}

.iw-error__retry:disabled {
  cursor: not-allowed;
  opacity: 0.4;
}

.iw-result {
  display: flex;
  flex-direction: column;
  margin-top: var(--s-3);
  overflow: hidden;
}

.iw-result__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-2) var(--s-3);
  padding: var(--s-3) var(--s-4);
  border-bottom: 1px solid var(--c-border);
}

.iw-result__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s-2);
  min-width: 0;
}

.iw-stale {
  flex: 0 0 auto;
  padding: 0 5px;
  border: 1px solid var(--c-border-strong);
  border-radius: var(--r-sm);
  color: var(--c-text-muted);
  font-size: var(--t-xs);
  line-height: 16px;
}

.iw-result__condition {
  margin: 0;
  color: var(--c-text);
  font-size: var(--t-sm);
}

.iw-result__count {
  margin: 0;
  color: var(--c-text-muted);
  font-size: var(--t-xs);
}

.iw-result__mismatch {
  margin-left: var(--s-1);
  color: var(--c-text-faint);
}

.iw-result__side {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s-2);
}

.iw-sizes {
  display: inline-flex;
  padding: 2px;
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface-sunken);
}

.iw-size {
  min-width: 34px;
  height: 28px;
  border: 0;
  border-radius: var(--r-sm);
  background: none;
  color: var(--c-text-muted);
  font-size: var(--t-xs);
  font-family: inherit;
  cursor: pointer;
}

.iw-size.is-active {
  background: var(--c-surface);
  color: var(--c-text);
  box-shadow: 0 1px 2px rgb(0 0 0 / 6%);
}

.iw-ai-btn {
  min-height: 34px;
  padding: 0 var(--s-4);
  font-size: var(--t-xs);
}

.iw-empty {
  padding: var(--s-10) var(--s-6);
}

.iw-empty__title {
  margin: 0;
  color: var(--c-text);
  font-size: var(--t-base);
}

.iw-empty__hint {
  margin: var(--s-2) 0 0;
  color: var(--c-text-faint);
  font-size: var(--t-xs);
}

/* 横向滚动容器同时充当纵向滚动容器，表头 sticky 才有意义；否则表头跟着页面滚走。
   嵌在弹窗里时由外层把 --iw-table-max 调小，避免和弹窗自身滚动叠加。 */
.iw-table-wrap {
  max-height: var(--iw-table-max, 62vh);
  overflow: auto;
}

.iw-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--t-sm);
}

.iw-table th {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: var(--s-2) var(--s-3);
  border-bottom: 1px solid var(--c-border);
  background: var(--c-surface-sunken);
  color: var(--c-text-muted);
  font-size: var(--t-xs);
  font-weight: 600;
  text-align: left;
  white-space: nowrap;
}

.iw-table td {
  max-width: 280px;
  padding: var(--s-2) var(--s-3);
  border-bottom: 1px solid var(--c-border);
  color: var(--c-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.iw-table tbody tr:last-child td {
  border-bottom: 0;
}

.iw-table tbody tr:hover td {
  background: var(--c-surface-sunken);
}

.iw-link {
  color: var(--c-brand);
  text-decoration: none;
}

.iw-link:hover {
  text-decoration: underline;
}

.iw-pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--s-4);
  padding: var(--s-3) var(--s-4);
  border-top: 1px solid var(--c-border);
}

.iw-pager__btn {
  min-height: 30px;
  padding: 0 var(--s-3);
  border: 1px solid var(--c-border-strong);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  color: var(--c-text);
  font-size: var(--t-xs);
  font-family: inherit;
  cursor: pointer;
}

.iw-pager__btn:disabled {
  cursor: not-allowed;
  opacity: 0.4;
}

.iw-pager__label {
  color: var(--c-text-muted);
  font-size: var(--t-xs);
}

.iw-intro {
  margin-top: var(--s-5);
}

.iw-note {
  margin: var(--s-4) 0 0;
  color: var(--c-text-faint);
  font-size: var(--t-xs);
  line-height: 1.6;
}

@media (max-width: 720px) {
  .iw-search {
    flex-direction: column;
  }

  .iw-search__btn,
  .iw-optimize__btn {
    width: 100%;
  }

  .iw-result__side {
    width: 100%;
  }

  .iw-ai-btn {
    flex: 1 1 auto;
  }
}
</style>
