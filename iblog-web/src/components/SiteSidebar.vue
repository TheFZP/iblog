<template>
  <aside class="sidebar">
    <!-- 作者卡 -->
    <section v-if="site.author_name" class="block author-block">
      <div class="author-head">
        <img v-if="site.author_avatar" :src="site.author_avatar" alt="" class="avatar" />
        <span v-else class="avatar avatar--fallback" aria-hidden="true">{{ initial }}</span>
        <div class="author-info">
          <div class="author-name">{{ site.author_name }}</div>
          <!-- 站点作者名本身就叫「博主」时这行会变成「博主 / 博主」，等于没信息，直接省掉 -->
          <div v-if="site.author_name !== '博主'" class="author-role">博主</div>
        </div>
      </div>
      <p v-if="site.author_intro" class="author-intro">{{ site.author_intro }}</p>
      <a v-if="site.github_url" class="author-link" :href="site.github_url" target="_blank" rel="noopener">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path
            d="M12 2a10 10 0 0 0-3.16 19.49c.5.09.68-.22.68-.48v-1.7c-2.78.6-3.37-1.34-3.37-1.34-.45-1.16-1.11-1.47-1.11-1.47-.91-.62.07-.6.07-.6 1 .07 1.53 1.03 1.53 1.03.9 1.53 2.36 1.09 2.94.84.09-.65.35-1.09.63-1.34-2.22-.25-4.56-1.11-4.56-4.94 0-1.09.39-1.98 1.03-2.68-.1-.25-.45-1.27.1-2.64 0 0 .84-.27 2.75 1.02a9.5 9.5 0 0 1 5 0c1.91-1.29 2.75-1.02 2.75-1.02.55 1.37.2 2.39.1 2.64.64.7 1.03 1.59 1.03 2.68 0 3.84-2.34 4.69-4.57 4.94.36.31.68.92.68 1.85v2.75c0 .27.18.58.69.48A10 10 0 0 0 12 2Z"
          />
        </svg>
        GitHub
      </a>
    </section>

    <!-- 分类 -->
    <section v-if="categories.length" class="block">
      <div class="block-title">分类</div>
      <ul class="list">
        <li v-for="item in categories" :key="item.id">
          <router-link :to="{ path: '/list', query: { categoryId: item.id } }" class="list-link">
            <span class="list-name">{{ item.name }}</span>
            <span class="list-count">{{ item.articleCount || 0 }}</span>
          </router-link>
        </li>
      </ul>
    </section>

    <!-- 标签 -->
    <section v-if="tags.length" class="block">
      <div class="block-title">标签</div>
      <div class="tags">
        <router-link
          v-for="item in tags"
          :key="item.id"
          class="chip"
          :to="{ path: '/list', query: { tagId: item.id } }"
        >
          {{ item.name }}
        </router-link>
      </div>
    </section>

    <!-- 热门文章 -->
    <section v-if="hotArticles.length" class="block">
      <div class="block-title">热门文章</div>
      <ol class="hot">
        <li v-for="(item, index) in hotArticles" :key="item.id">
          <router-link :to="`/article/${item.id}`">
            <span class="hot-index" :class="{ 'hot-index--top': index < 3 }">{{ index + 1 }}</span>
            <span class="hot-title">{{ item.title }}</span>
          </router-link>
        </li>
      </ol>
    </section>
  </aside>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  getHotArticles,
  listCategories,
  listTags,
  type ArticleItem,
  type CategoryItem,
  type TagItem
} from '@/api/portal'
import { useSite } from '@/composables/useSite'

const { site, loadSite } = useSite()
const categories = ref<CategoryItem[]>([])
const tags = ref<TagItem[]>([])
const hotArticles = ref<ArticleItem[]>([])

const initial = computed(() => {
  const name = (site.value.author_name || '').trim()
  return /^[a-zA-Z]/.test(name) ? name[0].toUpperCase() : name.slice(0, 1)
})

onMounted(async () => {
  loadSite()
  // 三个接口互不依赖，任一失败也不该让整个侧栏空掉
  const [c, t, h] = await Promise.all([
    listCategories().catch(() => []),
    listTags().catch(() => []),
    getHotArticles().catch(() => [])
  ])
  categories.value = c
  tags.value = t
  hotArticles.value = h
})
</script>

<style scoped>
.sidebar {
  display: flex;
  flex-direction: column;
  gap: var(--s-8);
  position: sticky;
  top: calc(var(--header-h) + 24px);
}

@media (max-width: 1040px) {
  .sidebar {
    position: static;
    gap: var(--s-6);
  }
}

.block-title {
  margin-bottom: var(--s-3);
  font-size: var(--t-xs);
  font-weight: 640;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--c-text-faint);
}

/* ---------- 作者卡 ---------- */
.author-block {
  padding: var(--s-5);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-lg);
}

.author-head {
  display: flex;
  align-items: center;
  gap: var(--s-3);
}

.avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  object-fit: cover;
  flex-shrink: 0;
}

.avatar--fallback {
  display: grid;
  place-items: center;
  background: var(--c-brand);
  color: #fff;
  font-size: var(--t-lg);
  font-weight: 620;
}

.author-name {
  font-size: var(--t-base);
  font-weight: 660;
  color: var(--c-text);
  line-height: 1.3;
  letter-spacing: -0.015em;
}

.author-role {
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.author-intro {
  margin: var(--s-3) 0 0;
  font-size: var(--t-sm);
  line-height: 1.7;
  color: var(--c-text-muted);
}

.author-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: var(--s-3);
  padding-top: var(--s-3);
  border-top: 1px solid var(--c-border);
  width: 100%;
  font-size: var(--t-sm);
  color: var(--c-text-muted);
}

.author-link:hover {
  color: var(--c-brand);
}

.author-link svg {
  width: 15px;
  height: 15px;
  fill: currentColor;
}

/* ---------- 分类 ---------- */
.list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.list-link {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-2);
  padding: 8px 10px;
  margin: 0 -10px;
  border-radius: var(--r-sm);
  color: var(--c-text-secondary);
  font-size: 14px;
  transition: background-color 0.15s var(--ease), color 0.15s var(--ease);
}

.list-link:hover {
  background: var(--c-surface-sunken);
  color: var(--c-text);
}

.list-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.list-count {
  flex-shrink: 0;
  font-size: var(--t-xs);
  color: var(--c-text-faint);
  font-variant-numeric: tabular-nums;
}

/* ---------- 标签 ---------- */
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}

/* ---------- 热门 ---------- */
.hot {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.hot a {
  display: flex;
  gap: 10px;
  padding: 7px 0;
  color: var(--c-text-secondary);
  font-size: 14px;
  line-height: 1.55;
}

.hot a:hover {
  color: var(--c-brand);
}

.hot-index {
  flex-shrink: 0;
  width: 18px;
  font-size: var(--t-xs);
  font-weight: 640;
  color: var(--c-text-faint);
  font-variant-numeric: tabular-nums;
  line-height: 1.9;
}

/* 前三名给点强调，其余保持安静 —— 全都在强调就等于都没强调 */
.hot-index--top {
  color: var(--c-brand);
}

.hot-title {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
