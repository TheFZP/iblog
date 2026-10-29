<script setup lang="ts">
/**
 * 全局搜索弹窗（Ctrl+K）。
 *
 * <p>为什么单独做一个：原来顶栏那个搜索框只能搜文章，而且键盘用户要用鼠标去点。
 * 现在一次能搜出「文章 + 股票」两类，Ctrl+K 直接唤起，选完回车就跳。</p>
 *
 * <p>结果里文章走 `/list?keyword=`（沿用既有搜索页），股票直接跳详情页——
 * 股票不需要额外的列表页，用户要的就是那只票。</p>
 */
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { searchStock, type SearchResult } from '@/api/stock'
import { pageArticles } from '@/api/portal'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: [] }>()

const router = useRouter()

/** 输入内容 */
const keyword = ref('')
const loading = ref(false)
const error = ref('')

const articleHits = ref<Array<{ id: number; title: string }>>([])
const stockHits = ref<SearchResult[]>([])

/** 当前高亮的条目下标（两个列表拼起来算，键盘上下键统一移动） */
const cursor = ref(0)

const inputRef = ref<HTMLInputElement | null>(null)

interface Hit {
  kind: 'article' | 'stock'
  key: string
  title: string
  subtitle: string
  action: () => void
}

const hits = computed<Hit[]>(() => {
  const list: Hit[] = []
  for (const item of stockHits.value) {
    list.push({
      kind: 'stock',
      key: `stock-${item.code}`,
      title: item.name,
      subtitle: `${item.code} · 股票`,
      action: () => go(`/stock/${item.code}`)
    })
  }
  for (const item of articleHits.value) {
    list.push({
      kind: 'article',
      key: `article-${item.id}`,
      title: item.title,
      subtitle: '文章',
      action: () => go({ path: '/list', query: { keyword: keyword.value.trim() } })
    })
  }
  return list
})

/** 空输入时的提示：告诉用户能搜什么，而不是给一片空白 */
const showHint = computed(() => keyword.value.trim().length === 0)

/**
 * 请求序号：每次输入都发请求，慢的响应可能后到。
 * 只认最后一次的结果，否则会出现「输入 abc 却显示 ab 的结果」。
 */
let seq = 0
/** 输入防抖定时器 */
let debounceTimer: ReturnType<typeof setTimeout> | undefined

async function doSearch(text: string) {
  const query = text.trim()
  const my = ++seq
  if (!query) {
    articleHits.value = []
    stockHits.value = []
    error.value = ''
    loading.value = false
    return
  }
  loading.value = true
  error.value = ''
  try {
    // 两类并发发出：股票接口是必走的，文章接口挂了也不该让股票搜不出来
    const [articles, stocks] = await Promise.allSettled([
      pageArticles({ keyword: query, pageNum: 1, pageSize: 5 }),
      searchStock(query)
    ])
    if (my !== seq) {
      return
    }
    if (articles.status === 'fulfilled') {
      const page = articles.value
      const list = Array.isArray(page?.records) ? page.records : []
      articleHits.value = list
        .filter((item) => item && typeof item.id === 'number' && typeof item.title === 'string')
        .slice(0, 5)
        .map((item) => ({ id: item.id, title: item.title }))
    } else {
      articleHits.value = []
    }
    if (stocks.status === 'fulfilled') {
      stockHits.value = Array.isArray(stocks.value) ? stocks.value.slice(0, 8) : []
    } else {
      stockHits.value = []
    }
    cursor.value = 0
  } catch (e) {
    if (my !== seq) {
      return
    }
    error.value = e instanceof Error ? e.message : '搜索失败，请稍后重试'
  } finally {
    if (my === seq) {
      loading.value = false
    }
  }
}

watch(keyword, (value) => {
  if (debounceTimer !== undefined) {
    clearTimeout(debounceTimer)
  }
  // 250ms 防抖：打字过程中每个字符都打两个接口，上游很快就限流
  debounceTimer = setTimeout(() => {
    void doSearch(value)
  }, 250)
})

watch(
  () => props.open,
  (open) => {
    if (!open) {
      return
    }
    keyword.value = ''
    articleHits.value = []
    stockHits.value = []
    error.value = ''
    cursor.value = 0
    void nextTick(() => inputRef.value?.focus())
  }
)

function close() {
  emit('close')
}

function go(target: Parameters<typeof router.push>[0]) {
  close()
  void router.push(target)
}

function move(delta: number) {
  const total = hits.value.length
  if (total === 0) {
    return
  }
  // 循环移动：到底了再按下回到第一个，比卡住更符合直觉
  cursor.value = (cursor.value + delta + total) % total
}

function choose(index: number) {
  const hit = hits.value[index]
  if (hit) {
    hit.action()
  }
}

/** 回车：有高亮项就跳它，否则按当前输入当成文章关键词搜 */
function onEnter() {
  if (hits.value.length > 0) {
    choose(cursor.value)
    return
  }
  const query = keyword.value.trim()
  if (query) {
    go({ path: '/list', query: { keyword: query } })
  }
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    move(1)
    return
  }
  if (event.key === 'ArrowUp') {
    event.preventDefault()
    move(-1)
    return
  }
  if (event.key === 'Enter') {
    event.preventDefault()
    onEnter()
  }
}
</script>

<template>
  <Teleport to="body">
    <Transition name="gs-fade">
      <div v-if="open" class="gs-mask" @click.self="close">
        <div class="gs-box" role="dialog" aria-modal="true" aria-label="全局搜索">
          <div class="gs-input-row">
            <svg class="gs-icon" viewBox="0 0 24 24" aria-hidden="true">
              <circle cx="11" cy="11" r="7" />
              <path d="m20 20-3.6-3.6" />
            </svg>
            <input
              ref="inputRef"
              v-model="keyword"
              class="gs-input"
              type="search"
              placeholder="搜索文章或股票代码 / 名称"
              aria-label="搜索文章或股票"
              @keydown="onKeydown"
            />
            <kbd class="gs-esc">Esc</kbd>
          </div>

          <div v-if="showHint" class="gs-hint">
            <p>输入关键词开始搜索</p>
            <ul>
              <li><kbd>↑</kbd><kbd>↓</kbd> 移动</li>
              <li><kbd>Enter</kbd> 打开</li>
              <li><kbd>Esc</kbd> 关闭</li>
            </ul>
          </div>

          <p v-else-if="loading" class="gs-loading">搜索中…</p>

          <p v-else-if="error" class="gs-error">{{ error }}</p>

          <ul v-else-if="hits.length > 0" class="gs-list">
            <li
              v-for="(hit, index) in hits"
              :key="hit.key"
              class="gs-item"
              :class="{ 'is-active': index === cursor }"
              @mouseenter="cursor = index"
              @click="choose(index)"
            >
              <span class="gs-item__kind">{{ hit.kind === 'stock' ? '股票' : '文章' }}</span>
              <span class="gs-item__title">{{ hit.title }}</span>
              <span class="gs-item__sub">{{ hit.subtitle }}</span>
            </li>
          </ul>

          <p v-else class="gs-empty">没有找到「{{ keyword.trim() }}」相关的文章或股票</p>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.gs-mask {
  position: fixed;
  inset: 0;
  z-index: 210;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 12vh var(--s-4) var(--s-4);
  background: rgb(0 0 0 / 45%);
  backdrop-filter: blur(2px);
}

.gs-box {
  width: min(620px, 100%);
  max-height: 70vh;
  overflow: auto;
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface);
  box-shadow: 0 24px 60px rgb(0 0 0 / 28%);
}

.gs-input-row {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  padding: var(--s-3) var(--s-4);
  border-bottom: 1px solid var(--c-border);
}

.gs-icon {
  width: 18px;
  height: 18px;
  flex: 0 0 auto;
  fill: none;
  stroke: var(--c-text-faint);
  stroke-width: 1.8;
  stroke-linecap: round;
}

.gs-input {
  flex: 1 1 auto;
  min-width: 0;
  height: 32px;
  border: 0;
  background: transparent;
  color: var(--c-text);
  font-size: var(--t-base);
  font-family: inherit;
}

.gs-input:focus {
  outline: none;
}

.gs-esc,
.gs-hint kbd {
  padding: 1px 5px;
  border: 1px solid var(--c-border);
  border-radius: 4px;
  background: var(--c-surface-sunken);
  color: var(--c-text-faint);
  font-size: var(--t-xs);
  font-family: inherit;
}

.gs-hint,
.gs-loading,
.gs-error,
.gs-empty {
  margin: 0;
  padding: var(--s-4);
  color: var(--c-text-muted);
  font-size: var(--t-sm);
  text-align: center;
}

.gs-hint ul {
  display: flex;
  justify-content: center;
  gap: var(--s-4);
  margin: var(--s-3) 0 0;
  padding: 0;
  list-style: none;
  color: var(--c-text-faint);
  font-size: var(--t-xs);
}

.gs-error {
  color: var(--stock-up);
}

.gs-list {
  margin: 0;
  padding: var(--s-2);
  list-style: none;
}

.gs-item {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  padding: var(--s-2) var(--s-3);
  border-radius: var(--r-sm);
  cursor: pointer;
}

.gs-item.is-active {
  background: var(--c-surface-sunken);
}

.gs-item__kind {
  flex: 0 0 auto;
  padding: 1px 6px;
  border-radius: var(--r-full);
  background: var(--c-surface-sunken);
  color: var(--c-text-faint);
  font-size: var(--t-xs);
}

.gs-item.is-active .gs-item__kind {
  background: var(--c-surface);
}

.gs-item__title {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  color: var(--c-text);
  font-size: var(--t-sm);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.gs-item__sub {
  flex: 0 0 auto;
  color: var(--c-text-faint);
  font-size: var(--t-xs);
}

.gs-fade-enter-active,
.gs-fade-leave-active {
  transition: opacity 0.15s var(--ease);
}

.gs-fade-enter-from,
.gs-fade-leave-to {
  opacity: 0;
}

@media (max-width: 720px) {
  .gs-mask {
    padding-top: 8vh;
  }

  .gs-box {
    max-height: 76vh;
  }
}
</style>
