<template>
  <article class="article-card card">
    <router-link :to="`/article/${article.id}`" class="title">{{ article.title }}</router-link>

    <p v-if="article.summary" class="summary">{{ article.summary }}</p>

    <div class="meta">
      <span v-if="article.categoryName" class="meta-item">{{ article.categoryName }}</span>
      <span class="meta-item">{{ formatDate(article.publishTime) }}</span>
      <span class="meta-item">阅读 {{ article.viewCount || 0 }}</span>
      <span class="meta-item">评论 {{ article.commentCount || 0 }}</span>
      <span v-for="name in article.tagNames || []" :key="name" class="tag">{{ name }}</span>
    </div>
  </article>
</template>

<script setup lang="ts">
import type { ArticleItem } from '@/api/portal'
import { formatDate } from '@/utils/format'

defineProps<{ article: ArticleItem }>()
</script>

<style scoped>
.article-card {
  padding: 20px 22px;
  margin-bottom: 16px;
  transition: box-shadow 0.2s;
}

.article-card:hover {
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.06);
}

.title {
  display: block;
  font-size: 19px;
  font-weight: 600;
  color: var(--iblog-text);
  margin-bottom: 8px;
}

.title:hover {
  color: var(--iblog-primary);
}

.summary {
  margin: 0 0 12px;
  color: #5c6b7a;
  font-size: 14px;
}

.meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 14px;
  font-size: 12px;
  color: var(--iblog-muted);
}

.tag {
  background: #eef4fa;
  color: var(--iblog-primary);
  border-radius: 10px;
  padding: 1px 10px;
}
</style>
