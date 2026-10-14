<template>
  <div>
    <el-row :gutter="16">
      <el-col v-for="card in cards" :key="card.label" :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="label">{{ card.label }}</div>
          <div class="value">{{ card.value }}</div>
          <div v-if="card.extra" class="extra">{{ card.extra }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="recent-card">
      <template #header>
        <div class="card-header">
          <span>最新文章</span>
          <el-button link type="primary" @click="$router.push('/articles')">全部文章</el-button>
        </div>
      </template>
      <el-table :data="stats.latestArticles || []" v-loading="loading" style="width: 100%">
        <el-table-column prop="title" label="标题" min-width="240" show-overflow-tooltip />
        <el-table-column prop="categoryName" label="分类" width="120" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '已发布' : '草稿' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="viewCount" label="浏览" width="80" />
        <el-table-column prop="publishTime" label="发布时间" width="170" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="primary" @click="$router.push(`/articles/edit/${row.id}`)">
              编辑
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getDashboard } from '@/api/article'

const loading = ref(false)
const stats = ref<Record<string, any>>({})

const cards = computed(() => [
  {
    label: '文章总数',
    value: stats.value.articleCount ?? 0,
    extra: `已发布 ${stats.value.publishedCount ?? 0} / 草稿 ${stats.value.draftCount ?? 0}`
  },
  { label: '分类 / 标签', value: `${stats.value.categoryCount ?? 0} / ${stats.value.tagCount ?? 0}` },
  {
    label: '评论总数',
    value: stats.value.commentCount ?? 0,
    extra: `待审核 ${stats.value.pendingCommentCount ?? 0}`
  },
  { label: '累计阅读', value: stats.value.totalViewCount ?? 0 }
])

async function load() {
  loading.value = true
  try {
    stats.value = await getDashboard()
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.stat-card {
  margin-bottom: 16px;
}

.stat-card .label {
  color: #909399;
  font-size: 13px;
}

.stat-card .value {
  font-size: 26px;
  font-weight: 600;
  margin: 8px 0 4px;
}

.stat-card .extra {
  color: #a8abb2;
  font-size: 12px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
