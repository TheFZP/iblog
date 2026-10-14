<template>
  <div class="app-layout">
    <header class="header">
      <div class="layout header-inner">
        <router-link to="/" class="brand">
          <span class="brand-name">{{ site.site_title || 'iblog' }}</span>
          <span class="brand-sub">{{ site.site_subtitle || '记录技术与生活' }}</span>
        </router-link>

        <nav class="nav">
          <router-link to="/" :class="{ active: isActive('/') }">首页</router-link>
          <router-link to="/archive" :class="{ active: isActive('/archive') }">归档</router-link>
          <router-link to="/about" :class="{ active: isActive('/about') }">关于</router-link>
        </nav>

        <form class="search" @submit.prevent="onSearch">
          <input v-model="keyword" type="text" placeholder="搜索文章标题…" />
          <button type="submit">搜索</button>
        </form>
      </div>
    </header>

    <main class="main">
      <router-view />
    </main>

    <footer class="footer">
      <div class="layout">
        <p>© {{ year }} {{ site.site_title || 'iblog' }} · {{ site.site_footer || 'Powered by iblog' }}</p>
        <p v-if="site.site_icp" class="icp">{{ site.site_icp }}</p>
      </div>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSite } from '@/composables/useSite'

const route = useRoute()
const router = useRouter()
const { site, loadSite } = useSite()

const keyword = ref('')
const year = computed(() => new Date().getFullYear())

function isActive(path: string) {
  if (path === '/') {
    return route.path === '/'
  }
  return route.path.startsWith(path)
}

function onSearch() {
  router.push({ path: '/list', query: { keyword: keyword.value || undefined } })
}

onMounted(loadSite)
</script>

<style scoped>
.app-layout {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.header {
  background: #fff;
  border-bottom: 1px solid var(--iblog-border);
  position: sticky;
  top: 0;
  z-index: 10;
}

.header-inner {
  height: 68px;
  display: flex;
  align-items: center;
  gap: 32px;
}

.brand {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}

.brand-name {
  font-size: 20px;
  font-weight: 700;
  color: var(--iblog-text);
}

.brand-sub {
  font-size: 12px;
  color: var(--iblog-muted);
}

.nav {
  display: flex;
  gap: 22px;
}

.nav a {
  color: var(--iblog-text);
  font-size: 15px;
  padding: 4px 0;
  border-bottom: 2px solid transparent;
}

.nav a.active {
  color: var(--iblog-primary);
  border-bottom-color: var(--iblog-primary);
}

.search {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}

.search input {
  width: 200px;
  height: 34px;
  padding: 0 12px;
  border: 1px solid var(--iblog-border);
  border-radius: 17px;
  outline: none;
  font-size: 14px;
}

.search input:focus {
  border-color: var(--iblog-primary);
}

.search button {
  height: 34px;
  padding: 0 16px;
  border: none;
  border-radius: 17px;
  background: var(--iblog-primary);
  color: #fff;
  cursor: pointer;
  font-size: 14px;
}

.main {
  flex: 1;
  padding: 28px 0 48px;
}

.footer {
  background: #fff;
  border-top: 1px solid var(--iblog-border);
  padding: 22px 0;
  text-align: center;
  color: var(--iblog-muted);
  font-size: 13px;
}

.footer p {
  margin: 4px 0;
}

.icp {
  font-size: 12px;
}
</style>
