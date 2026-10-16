<template>
  <div class="layout page-grid">
    <div class="main-col">
      <div class="head card">
        <h1>{{ headTitle }}</h1>
        <p v-if="headDesc">{{ headDesc }}</p>
      </div>

      <ArticleCard v-for="item in articles" :key="item.id" :article="item" />

      <div v-if="!loading && articles.length === 0" class="empty card">这里还没有文章。</div>

      <div class="pager">
        <button :disabled="pageNum <= 1" @click="changePage(pageNum - 1)">上一页</button>
        <span>{{ pageNum }} / {{ totalPages }}</span>
        <button :disabled="pageNum >= totalPages" @click="changePage(pageNum + 1)">下一页</button>
      </div>
    </div>

    <SiteSidebar />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import ArticleCard from '@/components/ArticleCard.vue'
import SiteSidebar from '@/components/SiteSidebar.vue'
import { listCategories, listTags, pageArticles, type ArticleItem } from '@/api/portal'

const route = useRoute()

const articles = ref<ArticleItem[]>([])
const loading = ref(false)
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)
const headTitle = ref('文章列表')
const headDesc = ref('')

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

async function load() {
  loading.value = true
  try {
    const categoryId = route.query.categoryId ? Number(route.query.categoryId) : null
    const tagId = route.query.tagId ? Number(route.query.tagId) : null
    const keyword = (route.query.keyword as string) || ''

    const data = await pageArticles({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      categoryId,
      tagId,
      keyword
    })
    articles.value = data.records || []
    total.value = data.total || 0
    await resolveHeader(categoryId, tagId, keyword)
  } finally {
    loading.value = false
  }
}

async function resolveHeader(categoryId: number | null, tagId: number | null, keyword: string) {
  if (categoryId) {
    const categories = await listCategories()
    const found = categories.find((item) => item.id === categoryId)
    headTitle.value = found ? `分类：${found.name}` : '分类'
    headDesc.value = found?.description || ''
  } else if (tagId) {
    const tags = await listTags()
    const found = tags.find((item) => item.id === tagId)
    headTitle.value = found ? `标签：${found.name}` : '标签'
    headDesc.value = ''
  } else if (keyword) {
    headTitle.value = `搜索：${keyword}`
    headDesc.value = `共找到 ${total.value} 篇相关文章`
  } else {
    headTitle.value = '文章列表'
    headDesc.value = ''
  }
}

function changePage(page: number) {
  pageNum.value = page
  load()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

watch(
  () => route.fullPath,
  () => {
    pageNum.value = 1
    load()
  }
)

onMounted(load)
</script>

<style scoped>
.page-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 20px;
  align-items: start;
}

.head {
  padding: 20px 24px;
  margin-bottom: 16px;
}

.head h1 {
  margin: 0;
  font-size: 20px;
}

.head p {
  margin: 6px 0 0;
  color: var(--c-text-muted);
  font-size: 14px;
}

.empty {
  padding: 40px;
  text-align: center;
  color: var(--c-text-muted);
}

.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-top: 20px;
  color: var(--c-text-muted);
  font-size: 14px;
}

.pager button {
  padding: 6px 16px;
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface);
  cursor: pointer;
  color: var(--c-text);
}

.pager button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
</style>
