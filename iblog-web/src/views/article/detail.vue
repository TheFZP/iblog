<template>
  <div class="layout narrow-article">
    <div v-if="article" class="card block">
      <h1 class="title">{{ article.title }}</h1>

      <div class="meta">
        <span v-if="article.categoryName" class="meta-item">{{ article.categoryName }}</span>
        <span class="meta-item">{{ formatDate(article.publishTime, true) }}</span>
        <span class="meta-item">阅读 {{ article.viewCount || 0 }}</span>
        <span class="meta-item">约 {{ readingTime }} 分钟</span>
      </div>

      <div class="tags" v-if="(article.tagNames || []).length">
        <span v-for="name in article.tagNames" :key="name" class="tag">{{ name }}</span>
      </div>

      <!-- eslint-disable-next-line vue/no-v-html -->
      <div class="markdown-body content" v-html="html"></div>

      <div class="neighbors">
        <div class="neighbor">
          <span class="label">上一篇</span>
          <router-link v-if="article.prevId" :to="`/article/${article.prevId}`">
            {{ article.prevTitle }}
          </router-link>
          <span v-else class="none">没有了</span>
        </div>
        <div class="neighbor right">
          <span class="label">下一篇</span>
          <router-link v-if="article.nextId" :to="`/article/${article.nextId}`">
            {{ article.nextTitle }}
          </router-link>
          <span v-else class="none">没有了</span>
        </div>
      </div>
    </div>

    <!-- 评论 -->
    <div class="card block">
      <h2 class="section-title">评论（{{ comments.length }}）</h2>

      <div v-if="comments.length" class="comment-list">
        <div v-for="item in comments" :key="item.id" class="comment">
          <div class="comment-head">
            <span class="nickname">{{ item.nickname }}</span>
            <span class="time">{{ formatDate(item.createTime, true) }}</span>
          </div>
          <div class="comment-body">{{ item.content }}</div>
        </div>
      </div>
      <p v-else class="none-tip">还没有评论，来说点什么吧。</p>

      <template v-if="commentEnabled">
        <h2 class="section-title">发表评论</h2>
        <form class="comment-form" @submit.prevent="onSubmit">
          <div class="row">
            <input v-model="form.nickname" type="text" placeholder="昵称 *" maxlength="30" />
            <input v-model="form.email" type="email" placeholder="邮箱（可选，不公开）" />
          </div>
          <textarea v-model="form.content" rows="4" placeholder="说点什么吧… *" maxlength="1000"></textarea>
          <div class="form-footer">
            <span class="hint">提交后需经博主审核才会展示</span>
            <button type="submit" :disabled="submitting">提交评论</button>
          </div>
        </form>
      </template>
      <p v-else class="none-tip">评论功能已关闭。</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  getArticle,
  listComments,
  submitComment,
  type ArticleItem,
  type CommentItem
} from '@/api/portal'
import { useSite } from '@/composables/useSite'
import { estimateReadingTime, formatDate } from '@/utils/format'
import { renderMarkdown } from '@/utils/markdown'

const route = useRoute()
const { site, loadSite } = useSite()

const article = ref<ArticleItem | null>(null)
const comments = ref<CommentItem[]>([])
const submitting = ref(false)

const form = reactive({ nickname: '', email: '', content: '' })

const html = computed(() => renderMarkdown(article.value?.content))
const readingTime = computed(() => estimateReadingTime(article.value?.content))
const commentEnabled = computed(() => site.value.comment_enabled !== '0')

async function load() {
  const id = Number(route.params.id)
  if (!id) {
    return
  }
  article.value = await getArticle(id)
  comments.value = await listComments(id)
  document.title = `${article.value.title} - ${site.value.site_title || 'iblog'}`
}

async function onSubmit() {
  if (!form.nickname.trim() || !form.content.trim()) {
    window.alert('昵称和评论内容不能为空')
    return
  }
  if (!article.value) {
    return
  }
  submitting.value = true
  try {
    await submitComment({
      articleId: article.value.id,
      nickname: form.nickname.trim(),
      email: form.email.trim(),
      content: form.content.trim()
    })
    form.content = ''
    window.alert('评论已提交，待博主审核后展示')
  } finally {
    submitting.value = false
  }
}

watch(() => route.params.id, load)

onMounted(async () => {
  await loadSite()
  load()
})
</script>

<style scoped>
.narrow-article {
  max-width: 820px;
}

.block {
  padding: 28px 34px;
  margin-bottom: 18px;
}

.title {
  margin: 0 0 12px;
  font-size: 26px;
  line-height: 1.4;
}

.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  color: var(--c-text-muted);
  font-size: 13px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--c-border);
}

.tags {
  display: flex;
  gap: 8px;
  margin-top: 14px;
  flex-wrap: wrap;
}

.tag {
  background: var(--c-brand-soft);
  color: var(--c-brand);
  border-radius: 12px;
  padding: 2px 12px;
  font-size: 13px;
}

.content {
  margin-top: 20px;
}

.neighbors {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  margin-top: 34px;
  padding-top: 18px;
  border-top: 1px solid var(--c-border);
  font-size: 14px;
}

.neighbor.right {
  text-align: right;
}

.neighbor .label {
  display: block;
  color: var(--c-text-muted);
  font-size: 12px;
  margin-bottom: 4px;
}

.none {
  color: var(--c-text-faint);
}

.section-title {
  font-size: 16px;
  margin: 0 0 14px;
}

.comment-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin-bottom: 8px;
}

.comment {
  padding: 12px 14px;
  background: var(--c-surface-sunken);
  border-radius: var(--r);
}

.comment-head {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: var(--c-text-muted);
  margin-bottom: 6px;
}

.nickname {
  color: var(--c-text);
  font-weight: 600;
}

.comment-body {
  font-size: 14px;
  white-space: pre-wrap;
}

.none-tip {
  color: var(--c-text-muted);
  font-size: 14px;
}

.comment-form .row {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}

.comment-form input,
.comment-form textarea {
  width: 100%;
  padding: 9px 12px;
  border: 1px solid var(--c-border);
  border-radius: 6px;
  font-size: 14px;
  font-family: inherit;
  outline: none;
  resize: vertical;
}

.comment-form input:focus,
.comment-form textarea:focus {
  border-color: var(--c-brand);
}

.form-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
}

.hint {
  color: var(--c-text-muted);
  font-size: 12px;
}

.form-footer button {
  padding: 8px 22px;
  border: none;
  border-radius: 6px;
  background: var(--c-brand);
  color: #fff;
  cursor: pointer;
  font-size: 14px;
}

.form-footer button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
