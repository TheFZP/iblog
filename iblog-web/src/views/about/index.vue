<template>
  <div class="layout narrow">
    <div class="card block">
      <h1>关于本站</h1>
      <p class="desc">{{ site.site_description || '记录技术与生活' }}</p>

      <div class="author">
        <img v-if="site.author_avatar" :src="site.author_avatar" alt="avatar" class="avatar" />
        <div>
          <div class="name">{{ site.author_name || '博主' }}</div>
          <div class="intro">{{ site.author_intro || '一个正在认真写代码的人。' }}</div>
        </div>
      </div>

      <ul class="info">
        <li v-if="site.email"><span>邮箱</span>{{ site.email }}</li>
        <li v-if="site.github_url">
          <span>GitHub</span>
          <a :href="site.github_url" target="_blank" rel="noopener">{{ site.github_url }}</a>
        </li>
      </ul>

      <div class="stack">
        <h2>技术栈</h2>
        <ul>
          <li>后端：Java 17 · Spring Boot 3 · MyBatis-Plus</li>
          <li>数据库：MySQL 8 · Redis</li>
          <li>管理后台：Vue 3 · Vite · Element Plus</li>
          <li>官网前台：Vue 3 · Vite</li>
          <li>部署：Docker Compose · Nginx · 阿里云</li>
        </ul>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { useSite } from '@/composables/useSite'

const { site, loadSite } = useSite()

onMounted(loadSite)
</script>

<style scoped>
.narrow {
  max-width: 760px;
}

.block {
  padding: 28px 32px;
}

h1 {
  margin: 0 0 8px;
  font-size: 22px;
}

.desc {
  color: var(--iblog-muted);
  margin: 0 0 22px;
}

.author {
  display: flex;
  gap: 16px;
  align-items: center;
  padding: 16px 0;
  border-top: 1px solid var(--iblog-border);
  border-bottom: 1px solid var(--iblog-border);
}

.avatar {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  object-fit: cover;
}

.name {
  font-size: 17px;
  font-weight: 600;
}

.intro {
  color: var(--iblog-muted);
  font-size: 14px;
}

.info {
  list-style: none;
  padding: 0;
  margin: 16px 0 0;
}

.info li {
  padding: 4px 0;
  font-size: 14px;
}

.info span {
  display: inline-block;
  width: 70px;
  color: var(--iblog-muted);
}

.stack {
  margin-top: 26px;
}

.stack h2 {
  font-size: 17px;
  margin: 0 0 10px;
}

.stack ul {
  margin: 0;
  padding-left: 1.4em;
  color: #4a5a6a;
}
</style>
