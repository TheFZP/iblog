<template>
  <article class="card" :class="[`card--${variant}`]">
    <router-link :to="`/article/${article.id}`" class="card-link">
      <!-- 封面：后端没填 cover 时不渲染，避免占位图喧宾夺主 -->
      <div v-if="article.cover" class="cover">
        <img
          :src="article.cover"
          :alt="article.title"
          :loading="priority ? 'eager' : 'lazy'"
          :fetchpriority="priority ? 'high' : 'auto'"
          decoding="async"
        />
      </div>

      <div class="body">
        <div class="top">
          <span v-if="article.isTop === 1" class="pin">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M12 17v5M9 3h6l-1 6 3 3v2H7v-2l3-3-1-6Z" />
            </svg>
            置顶
          </span>
          <span v-if="article.categoryName" class="category">{{ article.categoryName }}</span>
        </div>

        <h3 class="title">{{ article.title }}</h3>

        <p v-if="article.summary" class="summary">{{ article.summary }}</p>

        <div class="meta">
          <time :datetime="article.publishTime || ''">{{ formatDate(article.publishTime) }}</time>
          <span class="sep" aria-hidden="true">·</span>
          <span>{{ article.viewCount || 0 }} 次阅读</span>
          <template v-if="variant !== 'compact' && (article.tagNames || []).length">
            <span class="sep" aria-hidden="true">·</span>
            <span class="tags">
              <span v-for="name in (article.tagNames || []).slice(0, 3)" :key="name" class="chip">
                {{ name }}
              </span>
            </span>
          </template>
        </div>
      </div>
    </router-link>
  </article>
</template>

<script setup lang="ts">
import type { ArticleItem } from '@/api/portal'
import { formatDate } from '@/utils/format'

withDefaults(
  defineProps<{
    article: ArticleItem
    /** featured = 首屏头条（大图叠字） · compact = 归档/侧栏（纯文字） · grid = 首屏次要（上图下文） · 默认 = 标准横向卡片 */
    variant?: 'default' | 'featured' | 'compact' | 'grid'
    /** 首屏可见的卡片置 true，封面立即加载，避免首屏留灰块等待 */
    priority?: boolean
  }>(),
  { variant: 'default', priority: false }
)
</script>

<style scoped>
.card {
  position: relative;
}

.card-link {
  display: block;
  height: 100%;
  color: inherit;
}

.card-link:hover {
  color: inherit;
}

.cover {
  overflow: hidden;
  background: var(--c-surface-sunken);
}

.cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.55s var(--ease-out);
}

.card:hover .cover img {
  transform: scale(1.045);
}

.title {
  margin: 0;
  font-weight: 640;
  color: var(--c-text);
  letter-spacing: -0.018em;
  transition: color 0.16s var(--ease);
}

.summary {
  color: var(--c-text-muted);
  display: -webkit-box;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s-2);
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.meta time {
  font-variant-numeric: tabular-nums;
}

.sep {
  color: var(--c-border-strong);
}

.tags {
  display: inline-flex;
  gap: 5px;
  flex-wrap: wrap;
}

.pin {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: var(--t-xs);
  font-weight: 620;
  color: var(--c-accent);
}

.pin svg {
  width: 12px;
  height: 12px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.category {
  font-size: var(--t-xs);
  font-weight: 620;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--c-brand);
}

/* =============================================================
 *  标准卡片：横向 —— 左文右图。用于首页列表与列表页。
 *  不用卡片外框，只有一条底部分隔线，让列表读起来像一份目录。
 * ============================================================= */
.card--default {
  border-bottom: 1px solid var(--c-border);
}

.card--default:last-child {
  border-bottom: 0;
}

.card--default .card-link {
  display: flex;
  flex-direction: row-reverse;
  align-items: flex-start;
  gap: var(--s-8);
  padding: var(--s-8) 0;
}

.card--default:first-child .card-link {
  padding-top: 0;
}

.card--default .cover {
  flex: 0 0 232px;
  height: 150px;
  border-radius: var(--r);
}

.card--default .body {
  min-width: 0;
  flex: 1;
  padding-top: 2px;
}

.card--default .top {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  margin-bottom: var(--s-2);
}

.card--default .title {
  font-size: var(--t-xl);
  line-height: 1.38;
}

.card--default .summary {
  margin-top: var(--s-2);
  font-size: var(--t-base);
  line-height: 1.72;
  -webkit-line-clamp: 2;
  line-clamp: 2;
}

.card--default .meta {
  margin-top: var(--s-4);
}

.card--default:hover .title {
  color: var(--c-brand);
}

/* =============================================================
 *  头条：大图 + 图上叠字。首屏只用一次，制造绝对主次。
 * ============================================================= */
.card--featured .card-link {
  position: relative;
  display: flex;
  align-items: flex-end;
  min-height: 420px;
  border-radius: var(--r-lg);
  overflow: hidden;
  background: var(--c-surface-sunken);
}

.card--featured .cover {
  position: absolute;
  inset: 0;
}

.card--featured .cover::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(
    to top,
    rgba(10, 12, 14, 0.9) 0%,
    rgba(10, 12, 14, 0.72) 32%,
    rgba(10, 12, 14, 0.28) 62%,
    rgba(10, 12, 14, 0.12) 100%
  );
}

.card--featured .body {
  position: relative;
  z-index: 1;
  padding: var(--s-10);
  max-width: 660px;
}

.card--featured .top {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  margin-bottom: var(--s-3);
}

.card--featured .category {
  color: #fff;
}

.card--featured .pin {
  color: #ffd8a8;
}

.card--featured .title {
  font-size: var(--t-3xl);
  line-height: 1.22;
  color: #fff;
  letter-spacing: -0.028em;
}

.card--featured .summary {
  margin-top: var(--s-4);
  font-size: var(--t-md);
  line-height: 1.7;
  color: rgba(255, 255, 255, 0.82);
  -webkit-line-clamp: 2;
  line-clamp: 2;
}

.card--featured .meta {
  margin-top: var(--s-5);
  color: rgba(255, 255, 255, 0.7);
}

.card--featured .sep {
  color: rgba(255, 255, 255, 0.35);
}

.card--featured .chip {
  background: rgba(255, 255, 255, 0.14);
  color: rgba(255, 255, 255, 0.88);
  backdrop-filter: blur(6px);
}

.card--featured:hover .title {
  color: #fff;
}

.card--featured:hover .cover img {
  transform: scale(1.035);
}

/* 无封面时头条退化为纯文字版式，不留空洞 */
.card--featured:not(:has(.cover)) .card-link {
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  align-items: center;
  min-height: 0;
}

.card--featured:not(:has(.cover)) .title {
  color: var(--c-text);
}

.card--featured:not(:has(.cover)) .summary {
  color: var(--c-text-muted);
}

.card--featured:not(:has(.cover)) .category {
  color: var(--c-brand);
}

.card--featured:not(:has(.cover)) .meta {
  color: var(--c-text-faint);
}

.card--featured:not(:has(.cover)) .sep {
  color: var(--c-border-strong);
}

/* =============================================================
 *  网格卡：上图下文。用于首屏头条下方的两张次要卡片，
 *  与横向列表拉开距离，避免头条之后立刻塌回普通列表。
 * ============================================================= */
.card--grid .card-link {
  display: flex;
  flex-direction: column;
  height: 100%;
  gap: var(--s-4);
}

.card--grid .cover {
  flex: none;
  height: 190px;
  border-radius: var(--r);
}

.card--grid .body {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}

.card--grid .top {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  margin-bottom: var(--s-2);
}

.card--grid .title {
  font-size: var(--t-lg);
  line-height: 1.4;
}

.card--grid .summary {
  margin-top: var(--s-2);
  font-size: 14.5px;
  line-height: 1.7;
  -webkit-line-clamp: 2;
  line-clamp: 2;
}

/* 底部对齐：两张卡的高度由内容决定，meta 钉在底边 */
.card--grid .meta {
  margin-top: auto;
  padding-top: var(--s-4);
}

.card--grid:hover .title {
  color: var(--c-brand);
}

/* 无封面时不留空洞：撑起一个纯文字版式 */
.card--grid:not(:has(.cover)) .card-link {
  gap: 0;
}

/* =============================================================
 *  紧凑：纯文字行。用于侧栏「热门文章」与密集列表。
 * ============================================================= */
.card--compact .card-link {
  display: block;
  padding: var(--s-3) 0;
}

.card--compact .body {
  min-width: 0;
}

.card--compact .top {
  display: none;
}

.card--compact .title {
  font-size: 14.5px;
  font-weight: 560;
  line-height: 1.5;
  /* 最多两行，超长标题截断 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.card--compact .summary {
  display: none;
}

.card--compact .meta {
  margin-top: 5px;
}

.card--compact:hover .title {
  color: var(--c-brand);
}

/* =============================================================
 *  响应式
 * ============================================================= */
@media (max-width: 720px) {
  .card--default .card-link {
    flex-direction: column;
    gap: var(--s-4);
    padding: var(--s-6) 0;
  }

  .card--default .cover {
    flex: none;
    width: 100%;
    height: 186px;
  }

  .card--default .title {
    font-size: var(--t-lg);
  }

  .card--grid .cover {
    height: 168px;
  }

  .card--grid .title {
    font-size: var(--t-base);
  }

  .card--featured .card-link {
    min-height: 320px;
  }

  .card--featured .body {
    padding: var(--s-6);
  }

  .card--featured .title {
    font-size: var(--t-2xl);
  }

  .card--featured .summary {
    font-size: var(--t-base);
    -webkit-line-clamp: 3;
    line-clamp: 3;
  }
}
</style>
