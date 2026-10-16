<template>
  <div class="layout page-grid">
    <div class="main-col">
      <div class="head card">
        <h1>文章归档</h1>
        <p>共 {{ totalCount }} 篇文章</p>
      </div>

      <div v-for="(items, month) in archive" :key="month" class="card month-block">
        <div class="month">{{ month }} <span class="count">（{{ items.length }} 篇）</span></div>
        <ul>
          <li v-for="item in items" :key="item.id">
            <span class="date">{{ formatDate(item.publishTime) }}</span>
            <router-link :to="`/article/${item.id}`">{{ item.title }}</router-link>
          </li>
        </ul>
      </div>

      <div v-if="Object.keys(archive).length === 0" class="empty card">暂无归档文章。</div>
    </div>

    <SiteSidebar />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import SiteSidebar from '@/components/SiteSidebar.vue'
import { getArchive, type ArticleItem } from '@/api/portal'
import { formatDate } from '@/utils/format'

const archive = ref<Record<string, ArticleItem[]>>({})

const totalCount = computed(() =>
  Object.values(archive.value).reduce((sum, items) => sum + items.length, 0)
)

onMounted(async () => {
  archive.value = await getArchive()
})
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

.month-block {
  padding: 18px 22px;
  margin-bottom: 14px;
}

.month {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 10px;
}

.count {
  color: var(--c-text-muted);
  font-size: 13px;
  font-weight: 400;
}

.month-block ul {
  margin: 0;
  padding: 0;
  list-style: none;
}

.month-block li {
  display: flex;
  gap: 14px;
  padding: 6px 0;
  font-size: 14px;
}

.date {
  color: var(--c-text-muted);
  flex: 0 0 88px;
  font-variant-numeric: tabular-nums;
}

.empty {
  padding: 40px;
  text-align: center;
  color: var(--c-text-muted);
}
</style>
