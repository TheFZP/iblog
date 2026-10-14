<template>
  <div class="layout page-grid">
    <div class="main-col">
      <div class="intro card">
        <h1>{{ site.site_title || 'iblog' }}</h1>
        <p>{{ site.site_description || '记录技术与生活' }}</p>
      </div>

      <ArticleCard v-for="item in articles" :key="item.id" :article="item" />

      <div v-if="!loading && articles.length === 0" class="empty card">还没有发布文章，先去后台写一篇吧。</div>

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
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

onMounted(() => {
  loadSite()
  load()
})
</script>

<style scoped>
.page-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 20px;
  align-items: start;
}

.intro {
  padding: 22px 24px;
  margin-bottom: 16px;
}

.intro h1 {
  margin: 0 0 6px;
  font-size: 22px;
}

.intro p {
  margin: 0;
  color: var(--iblog-muted);
  font-size: 14px;
}

.empty {
  padding: 40px;
  text-align: center;
  color: var(--iblog-muted);
}

.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-top: 20px;
  color: var(--iblog-muted);
  font-size: 14px;
}

.pager button {
  padding: 6px 16px;
  border: 1px solid var(--iblog-border);
  border-radius: 6px;
  background: #fff;
  cursor: pointer;
  color: var(--iblog-text);
}

.pager button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
</style>
