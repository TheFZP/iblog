<template>
  <div class="layout">
    <!-- 刊头：站名 + 一句话定位。编辑式排版里这是「报头」 -->
    <header class="masthead">
      <div class="masthead__text">
        <p class="masthead__eyebrow">{{ today }}</p>
        <h1 class="masthead__title">{{ site.site_title || 'iblog' }}</h1>
        <p class="masthead__desc">
          {{ site.site_description || site.site_subtitle || '记录技术与生活' }}
        </p>
      </div>
    </header>

    <div class="page-grid">
      <div class="main-col">
        <!-- 首屏：一条头条 + 两条次要，制造绝对主次 -->
        <template v-if="lead">
          <ArticleCard :article="lead" variant="featured" priority class="lead-card" />
        </template>

        <div v-if="secondary.length" class="secondary-grid">
          <ArticleCard v-for="item in secondary" :key="item.id" :article="item" variant="grid" priority />
        </div>

        <template v-if="rest.length">
          <div class="list">
            <ArticleCard v-for="item in rest" :key="item.id" :article="item" variant="default" />
          </div>
        </template>

        <!-- 加载骨架：占住版位，避免内容到位时跳变 -->
        <div v-if="loading && !articles.length" class="skeleton-list" aria-hidden="true">
          <div class="skeleton skeleton--lead"></div>
          <div v-for="n in 4" :key="n" class="skeleton skeleton--row"></div>
        </div>

        <div v-if="!loading && articles.length === 0" class="empty-state">
          <svg class="empty-state__icon" viewBox="0 0 24 24" aria-hidden="true">
            <path d="M4 19.5V5.2A1.2 1.2 0 0 1 5.2 4h9.3L20 9.5v10a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1Z" />
            <path d="M14.4 4v5.6H20M7.8 13.6h8.4M7.8 17h5.6" />
          </svg>
          <p>还没有发布文章，先去后台写一篇吧。</p>
        </div>

        <nav v-if="totalPages > 1" class="pager" aria-label="分页">
          <button class="pager__btn" :disabled="pageNum <= 1" @click="changePage(pageNum - 1)">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m14 6-6 6 6 6" /></svg>
            上一页
          </button>
          <span class="pager__status">
            第 <strong>{{ pageNum }}</strong> / {{ totalPages }} 页
          </span>
          <button class="pager__btn" :disabled="pageNum >= totalPages" @click="changePage(pageNum + 1)">
            下一页
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m10 6 6 6-6 6" /></svg>
          </button>
        </nav>
      </div>

      <SiteSidebar />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ArticleCard from '@/components/ArticleCard.vue'
import SiteSidebar from '@/components/SiteSidebar.vue'
import { pageArticles, type ArticleItem } from '@/api/portal'
import { useSite } from '@/composables/useSite'

const { site, loadSite } = useSite()

const articles = ref<ArticleItem[]>([])
const loading = ref(false)
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

/** 第一页把首条做成头条：有封面的优先，否则退化为纯文字版式 */
const lead = computed(() => (pageNum.value === 1 ? articles.value[0] : null))
const secondary = computed(() => (pageNum.value === 1 ? articles.value.slice(1, 3) : articles.value.slice(0, 2)))
const rest = computed(() => (pageNum.value === 1 ? articles.value.slice(3) : articles.value.slice(2)))

const today = computed(() =>
  new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' })
)

async function load() {
  loading.value = true
  try {
    const data = await pageArticles({ pageNum: pageNum.value, pageSize: pageSize.value })
    articles.value = data.records || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}

function changePage(page: number) {
  pageNum.value = page
  load()
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  window.scrollTo({ top: 0, behavior: reduce ? 'auto' : 'smooth' })
}

onMounted(() => {
  loadSite()
  load()
})
</script>

<style scoped>
/* ---------- 刊头 ---------- */
/* 编辑式排版里的「报头」：日期、站名、一句定位，用 2px 实线收口。
   这里不再放作者卡 —— 侧栏已经有同样一张，重复两遍反而抢了站名的焦点。 */
.masthead {
  padding-bottom: var(--s-8);
  margin-bottom: var(--s-10);
  border-bottom: 2px solid var(--c-text);
}

.masthead__eyebrow {
  font-size: var(--t-xs);
  font-weight: 600;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--c-text-faint);
  margin-bottom: var(--s-3);
}

.masthead__title {
  font-size: var(--t-4xl);
  line-height: 1.06;
  letter-spacing: -0.038em;
  font-weight: 700;
}

.masthead__desc {
  margin-top: var(--s-3);
  max-width: 52ch;
  color: var(--c-text-muted);
  font-size: var(--t-md);
  line-height: 1.7;
}

/* ---------- 首屏 ---------- */
.lead-card {
  margin-bottom: var(--s-8);
}

/* 两张次要卡片并排，与下方的横向列表形成版式差异 */
.secondary-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s-8);
  margin-bottom: var(--s-10);
}

.list {
  border-top: 1px solid var(--c-border);
}

/* ---------- 骨架 ---------- */
.skeleton-list {
  display: flex;
  flex-direction: column;
  gap: var(--s-6);
}

.skeleton--lead {
  height: 420px;
  border-radius: var(--r-lg);
}

.skeleton--row {
  height: 150px;
}

/* ---------- 响应式 ---------- */
@media (max-width: 900px) {
  .masthead__title {
    font-size: var(--t-3xl);
  }
}

@media (max-width: 720px) {
  .secondary-grid {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--s-6);
    margin-bottom: var(--s-8);
  }
}

@media (max-width: 640px) {
  .masthead__desc {
    font-size: var(--t-base);
  }
}
</style>
