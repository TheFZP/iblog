<template>
  <aside class="sidebar">
    <div class="card block" v-if="site.author_name">
      <div class="block-title">关于我</div>
      <div class="author">
        <img v-if="site.author_avatar" :src="site.author_avatar" alt="avatar" class="avatar" />
        <div>
          <div class="author-name">{{ site.author_name }}</div>
          <div class="author-intro">{{ site.author_intro }}</div>
        </div>
      </div>
    </div>

    <div class="card block">
      <div class="block-title">分类</div>
      <ul class="list">
        <li v-for="item in categories" :key="item.id">
          <router-link :to="{ path: '/list', query: { categoryId: item.id } }">
            {{ item.name }}
          </router-link>
          <span class="count">{{ item.articleCount || 0 }}</span>
        </li>
      </ul>
    </div>

    <div class="card block">
      <div class="block-title">标签</div>
      <div class="tags">
        <router-link
          v-for="item in tags"
          :key="item.id"
          class="tag"
          :to="{ path: '/list', query: { tagId: item.id } }"
        >
          {{ item.name }}
        </router-link>
      </div>
    </div>

    <div class="card block">
      <div class="block-title">热门文章</div>
      <ul class="list">
        <li v-for="item in hotArticles" :key="item.id">
          <router-link :to="`/article/${item.id}`" class="hot-title">{{ item.title }}</router-link>
        </li>
      </ul>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getHotArticles, listCategories, listTags, type ArticleItem, type CategoryItem, type TagItem } from '@/api/portal'
import { useSite } from '@/composables/useSite'

const { site, loadSite } = useSite()
const categories = ref<CategoryItem[]>([])
const tags = ref<TagItem[]>([])
const hotArticles = ref<ArticleItem[]>([])

onMounted(async () => {
  loadSite()
  const [c, t, h] = await Promise.all([listCategories(), listTags(), getHotArticles()])
  categories.value = c
  tags.value = t
  hotArticles.value = h
})
</script>

<style scoped>
.sidebar {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.block {
  padding: 16px 18px;
}

.block-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 12px;
  padding-left: 8px;
  border-left: 3px solid var(--iblog-primary);
}

.author {
  display: flex;
  gap: 12px;
  align-items: center;
}

.avatar {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  object-fit: cover;
}

.author-name {
  font-weight: 600;
}

.author-intro {
  font-size: 13px;
  color: var(--iblog-muted);
}

.list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.list li {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 0;
  font-size: 14px;
  color: var(--iblog-text);
}

.list li a {
  color: var(--iblog-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.list li a:hover {
  color: var(--iblog-primary);
}

.count {
  color: var(--iblog-muted);
  font-size: 12px;
}

.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.tag {
  background: #eef4fa;
  color: var(--iblog-primary);
  border-radius: 12px;
  padding: 2px 12px;
  font-size: 13px;
}

.hot-title {
  display: block;
  max-width: 100%;
}
</style>
