<template>
  <div class="app-layout">
    <!-- 键盘用户跳过导航直达正文 -->
    <a class="skip-link" href="#main">跳到主要内容</a>

    <header class="header" :class="{ 'is-scrolled': scrolled, 'is-open': menuOpen }">
      <div class="layout header-inner">
        <router-link to="/" class="brand" aria-label="返回首页">
          <span class="brand-mark" aria-hidden="true">
            <svg viewBox="0 0 24 24">
              <path d="M4 19.5V5.2A1.2 1.2 0 0 1 5.2 4h9.3L20 9.5v10a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1Z" />
              <path d="M14.4 4v5.6H20" />
              <path d="M7.8 13.6h8.4M7.8 17h5.6" />
            </svg>
          </span>
          <span class="brand-text">
            <span class="brand-name">{{ site.site_title || 'iblog' }}</span>
            <span class="brand-sub">{{ site.site_subtitle || '记录技术与生活' }}</span>
          </span>
        </router-link>

        <nav class="nav" aria-label="主导航">
          <router-link
            v-for="item in navs"
            :key="item.path"
            :to="item.path"
            class="nav-link"
            :class="{ active: isActive(item.path) }"
          >
            {{ item.label }}
          </router-link>
        </nav>

        <div class="actions">
          <form class="search" role="search" @submit.prevent="onSearch">
            <svg class="search-icon" viewBox="0 0 24 24" aria-hidden="true">
              <circle cx="11" cy="11" r="7" />
              <path d="m20 20-3.6-3.6" />
            </svg>
            <input
              v-model="keyword"
              type="search"
              placeholder="搜索文章"
              aria-label="搜索文章"
              @keydown.esc="keyword = ''"
            />
          </form>

          <button
            class="icon-btn"
            type="button"
            aria-label="设置（Ctrl+,）"
            title="设置（Ctrl+,）"
            @click="settingsOpen = true"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <circle cx="12" cy="12" r="3.2" />
              <path d="M12 3v2.2M12 18.8V21M3 12h2.2M18.8 12H21M5.6 5.6l1.6 1.6M16.8 16.8l1.6 1.6M18.4 5.6l-1.6 1.6M7.2 16.8l-1.6 1.6" />
            </svg>
          </button>

          <button
            v-if="authenticated"
            class="icon-btn"
            type="button"
            aria-label="AI 助手（Ctrl+N）"
            title="AI 助手（Ctrl+N）"
            @click="onOpenAi"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M12 3.4 13.9 9l5.6 1.9-5.6 1.9L12 18.4l-1.9-5.6L4.5 10.9 10.1 9Z" />
              <path d="M18.4 15.6l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8Z" />
            </svg>
          </button>

          <button
            class="icon-btn"
            type="button"
            :aria-label="theme === 'dark' ? '切换到浅色模式' : '切换到深色模式'"
            @click="toggleTheme"
          >
            <svg v-if="theme === 'dark'" viewBox="0 0 24 24" aria-hidden="true">
              <circle cx="12" cy="12" r="4.2" />
              <path d="M12 2.6v2M12 19.4v2M2.6 12h2M19.4 12h2M5.4 5.4l1.4 1.4M17.2 17.2l1.4 1.4M18.6 5.4l-1.4 1.4M6.8 17.2l-1.4 1.4" />
            </svg>
            <svg v-else viewBox="0 0 24 24" aria-hidden="true">
              <path d="M20.6 14.4A8.7 8.7 0 0 1 9.6 3.4a8.6 8.6 0 1 0 11 11Z" />
            </svg>
          </button>

          <button
            class="icon-btn menu-btn"
            type="button"
            :aria-expanded="menuOpen"
            aria-controls="mobile-nav"
            aria-label="打开菜单"
            @click="menuOpen = !menuOpen"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path v-if="!menuOpen" d="M4 7.5h16M4 12h16M4 16.5h16" />
              <path v-else d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </div>
      </div>

      <!-- 窄屏下拉导航 -->
      <transition name="drop">
        <nav v-if="menuOpen" id="mobile-nav" class="mobile-nav" aria-label="移动端导航">
          <div class="layout">
            <router-link
              v-for="item in navs"
              :key="item.path"
              :to="item.path"
              :class="{ active: isActive(item.path) }"
              @click="menuOpen = false"
            >
              {{ item.label }}
            </router-link>
            <form class="mobile-search" role="search" @submit.prevent="onSearch">
              <input v-model="keyword" type="search" placeholder="搜索文章" aria-label="搜索文章" />
            </form>
          </div>
        </nav>
      </transition>
    </header>

    <main id="main" class="main">
      <router-view v-slot="{ Component, route: r }">
        <transition name="page" mode="out-in">
          <component :is="Component" :key="r.path" />
        </transition>
      </router-view>
    </main>

    <footer class="footer">
      <div class="layout footer-inner">
        <div class="footer-brand-col">
          <span class="footer-brand">{{ site.site_title || 'iblog' }}</span>
          <p v-if="site.site_description" class="footer-desc">{{ site.site_description }}</p>
        </div>

        <nav class="footer-links" aria-label="页脚导航">
          <router-link v-for="item in navs" :key="item.path" :to="item.path">{{ item.label }}</router-link>
        </nav>

        <div class="footer-meta">
          <a v-if="site.github_url" :href="site.github_url" target="_blank" rel="noopener">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M9 19c-4.3 1.4-4.3-2.2-6-2.6m12 5.6v-3.6c0-1 .1-1.4-.5-2 2.3-.3 4.5-1.2 4.5-5a3.9 3.9 0 0 0-1.1-2.7 3.6 3.6 0 0 0-.1-2.7s-.9-.3-2.9 1.1a10 10 0 0 0-5.2 0C7.7 5.5 6.8 5.8 6.8 5.8a3.6 3.6 0 0 0-.1 2.7A3.9 3.9 0 0 0 5.6 11.2c0 3.8 2.2 4.7 4.5 5-.6.6-.6 1.2-.5 2v3.6" />
            </svg>
            GitHub
          </a>
          <a v-if="site.email" :href="`mailto:${site.email}`">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <rect x="3" y="5" width="18" height="14" rx="2" />
              <path d="m3.5 6.5 8.5 6 8.5-6" />
            </svg>
            邮箱
          </a>
          <a v-if="site.site_icp" href="https://beian.miit.gov.cn" target="_blank" rel="noopener">
            {{ site.site_icp }}
          </a>
        </div>
      </div>

      <div class="layout footer-bottom">
        <span>{{ site.site_footer || 'Powered by iblog' }}</span>
        <span>© {{ year }} {{ site.author_name || site.site_title || 'iblog' }}</span>
      </div>
    </footer>

    <!-- 回到顶部：滚动一屏后出现 -->
    <transition name="fade">
      <button v-if="scrolled" class="to-top" type="button" aria-label="回到顶部" @click="toTop">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 19V5M5.5 11.5 12 5l6.5 6.5" />
        </svg>
      </button>
    </transition>

    <!-- 全局 AI 助手：挂在布局层，切页面不重建，对话不丢 -->
    <GlobalAiEntry v-if="authenticated" />

    <!-- 全局搜索 / 设置：同样挂在布局层，任意页面都能唤起 -->
    <GlobalSearch :open="searchOpen" @close="searchOpen = false" />
    <SettingsDialog :open="settingsOpen" @close="settingsOpen = false" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSite, useTheme, initTheme } from '@/composables/useSite'
import { stopAlertWatcher, useAutoStartAlertWatcher } from '@/composables/useAlertWatcher'
import { useAuth } from '@/composables/useAuth'
import { takeStoredPreset, useGlobalAi } from '@/composables/useGlobalAi'
import GlobalAiEntry from '@/components/stock/GlobalAiEntry.vue'
import GlobalSearch from '@/components/GlobalSearch.vue'
import SettingsDialog from '@/components/SettingsDialog.vue'

const route = useRoute()
const router = useRouter()
const { site, loadSite } = useSite()
const { theme, toggleTheme } = useTheme()
const { authenticated, ensureAuth } = useAuth()
const { open: aiOpen, preset: aiPreset } = useGlobalAi()

/** 顶栏的 AI 按钮与 Ctrl+N 走同一条路：有预置内容就一起带上，否则直接开 */
function onOpenAi() {
  const stored = takeStoredPreset()
  if (stored) {
    aiPreset.value = stored
  }
  aiOpen.value = true
}

const keyword = ref('')
const menuOpen = ref(false)
const scrolled = ref(false)
const searchOpen = ref(false)
const settingsOpen = ref(false)
const year = new Date().getFullYear()

/**
 * 全局快捷键：Ctrl/Cmd+K 搜索、Ctrl/Cmd+, 设置。
 *
 * <p>Ctrl+N 由 {@code GlobalAiEntry} 自己接管，这里不重复注册——同一个键挂在
 * 两个组件上会双触发（一个开面板、一个又关掉）。</p>
 *
 * <p>用 `event.key` 而不是 `keyCode`：`,` 在不同键盘布局下 keyCode 不一样，
 * 但 `key` 永远是逗号。输入框里打字时要放过，否则用户想在搜索框里按 Ctrl+K
 * 清空关键词会变成弹窗。</p>
 */
function onGlobalKeydown(event: KeyboardEvent) {
  if (!(event.ctrlKey || event.metaKey) || event.altKey || event.shiftKey) {
    return
  }
  const key = event.key.toLowerCase()
  if (key === 'k') {
    event.preventDefault()
    searchOpen.value = !searchOpen.value
    return
  }
  if (key === ',') {
    event.preventDefault()
    settingsOpen.value = !settingsOpen.value
  }
}

interface NavItem {
  path: string
  label: string
  /** 需要登录态才展示（后端会校验，这里只是不给未登录用户无效入口） */
  authOnly?: boolean
}

const ALL_NAVS: NavItem[] = [
  { path: '/', label: '首页' },
  { path: '/list', label: '文章' },
  { path: '/archive', label: '归档' },
  { path: '/food', label: '美食' },
  { path: '/stock', label: '行情', authOnly: true },
  { path: '/stock/iwencai', label: '问财选股', authOnly: true },
  { path: '/paper', label: '模拟盘', authOnly: true },
  { path: '/predict', label: '次日看涨', authOnly: true },
  { path: '/tools', label: '工具箱' },
  { path: '/about', label: '关于' }
]

/** 「行情」「问财选股」「模拟盘」「次日看涨」只对登录过后台的用户可见：桌面导航、移动导航、页脚共用这一份 */
const navs = computed(() =>
  authenticated.value ? ALL_NAVS : ALL_NAVS.filter((item) => !item.authOnly)
)

/**
 * 当前路由是否落在该导航项下。
 *
 * <p>不再是简单的 startsWith：`/stock/iwencai` 本身也落在 `/stock` 前缀下，
 * 那样「行情」和「问财选股」会同时高亮。改为「命中项里取最长的那条」，
 * 即子路径优先——这也让以后再加 `/stock/xxx` 子页时不用改这里。</p>
 */
function isActive(path: string) {
  if (path === '/') {
    return route.path === '/'
  }
  const candidates = navs.value.filter(
    (item) => route.path === item.path || route.path.startsWith(`${item.path}/`)
  )
  if (candidates.length === 0) {
    return false
  }
  const longest = candidates.reduce((a, b) => (b.path.length > a.path.length ? b : a))
  return longest.path === path
}

function onSearch() {
  const k = keyword.value.trim()
  menuOpen.value = false
  router.push({ path: '/list', query: k ? { keyword: k } : {} })
}

/** 滚动用一个 rAF 节流，避免每次 scroll 都触发响应式更新 */
let ticking = false
function onScroll() {
  if (ticking) {
    return
  }
  ticking = true
  requestAnimationFrame(() => {
    scrolled.value = window.scrollY > 12
    ticking = false
  })
}

function toTop() {
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  window.scrollTo({ top: 0, behavior: reduce ? 'auto' : 'smooth' })
}

/** 路由变化时收起移动端菜单，避免“菜单还开着但页面已经跳走” */
watch(
  () => route.fullPath,
  () => {
    menuOpen.value = false
  }
)

/** 移动端菜单展开时锁住背景滚动 */
watch(menuOpen, (open) => {
  document.body.style.overflow = open ? 'hidden' : ''
})

// 提醒巡查挂在布局层而不是行情页：提醒的意义就在于「我不盯着的时候」也生效，
// 只有停在行情页才巡检等于没有提醒。
useAutoStartAlertWatcher()

// 登录态确认后「行情」入口自动出现；路由守卫已先行校验，这里通常命中缓存不额外发请求
void ensureAuth()

onMounted(() => {
  initTheme()
  loadSite()
  onScroll()
  window.addEventListener('scroll', onScroll, { passive: true })
  window.addEventListener('keydown', onGlobalKeydown)
})

onUnmounted(() => {
  window.removeEventListener('scroll', onScroll)
  window.removeEventListener('keydown', onGlobalKeydown)
  document.body.style.overflow = ''
  stopAlertWatcher()
})
</script>

<style scoped>
.app-layout {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

/* ---------- 顶栏 ---------- */
.header {
  position: sticky;
  top: 0;
  z-index: 50;
  background: color-mix(in srgb, var(--c-bg) 80%, transparent);
  backdrop-filter: saturate(180%) blur(16px);
  -webkit-backdrop-filter: saturate(180%) blur(16px);
  border-bottom: 1px solid transparent;
  transition: border-color 0.25s var(--ease), background-color 0.25s var(--ease);
}

/* 顶部时是「浮动」的，滚动后才出现发丝线，形成边界感 */
.header.is-scrolled {
  border-bottom-color: var(--c-border);
  background: color-mix(in srgb, var(--c-bg) 93%, transparent);
}

.header-inner {
  height: var(--header-h);
  display: flex;
  align-items: center;
  gap: var(--s-8);
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--c-text);
  flex-shrink: 0;
}

.brand-mark {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: var(--c-brand);
  color: #fff;
  flex-shrink: 0;
}

.brand-mark svg {
  width: 19px;
  height: 19px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.16;
}

.brand-name {
  font-size: var(--t-md);
  font-weight: 680;
  letter-spacing: -0.02em;
}

.brand-sub {
  font-size: var(--t-xs);
  color: var(--c-text-faint);
  letter-spacing: 0.01em;
}

/* ---------- 主导航 ---------- */
.nav {
  display: flex;
  gap: 2px;
  margin-left: var(--s-4);
}

.nav-link {
  position: relative;
  padding: 8px 12px;
  border-radius: var(--r-sm);
  color: var(--c-text-secondary);
  font-size: 14.5px;
  font-weight: 500;
  transition: color 0.15s var(--ease), background-color 0.15s var(--ease);
}

.nav-link:hover {
  color: var(--c-text);
  background: var(--c-surface-sunken);
}

.nav-link.active {
  color: var(--c-text);
  font-weight: 620;
}

/* 当前页用一小段短横标记，比整条下划线轻 */
.nav-link.active::after {
  content: '';
  position: absolute;
  left: 12px;
  right: 12px;
  bottom: 0;
  height: 2px;
  border-radius: 2px;
  background: var(--c-brand);
}

.actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: var(--s-2);
}

/* ---------- 搜索 ---------- */
.search {
  position: relative;
  display: flex;
  align-items: center;
}

.search-icon {
  position: absolute;
  left: 11px;
  width: 15px;
  height: 15px;
  fill: none;
  stroke: var(--c-text-faint);
  stroke-width: 2;
  stroke-linecap: round;
  pointer-events: none;
}

.search input {
  width: 180px;
  height: 36px;
  padding: 0 12px 0 33px;
  border: 1px solid var(--c-border);
  border-radius: var(--r-full);
  background: var(--c-surface);
  color: var(--c-text);
  font-size: var(--t-sm);
  outline: none;
  transition: border-color 0.15s var(--ease), width 0.28s var(--ease),
    box-shadow 0.15s var(--ease);
}

.search input::placeholder {
  color: var(--c-text-faint);
}

.search input:focus {
  width: 230px;
  border-color: var(--c-brand);
  box-shadow: 0 0 0 3px var(--c-brand-ring);
}

/* 去掉 search 类型自带的清除按钮，样式不受控 */
.search input::-webkit-search-cancel-button {
  -webkit-appearance: none;
}

.icon-btn {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border: 1px solid transparent;
  border-radius: var(--r);
  background: transparent;
  color: var(--c-text-muted);
  cursor: pointer;
  transition: background-color 0.15s var(--ease), color 0.15s var(--ease);
}

.icon-btn:hover {
  background: var(--c-surface-sunken);
  color: var(--c-text);
}

.icon-btn svg {
  width: 18px;
  height: 18px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.menu-btn {
  display: none;
}

/* ---------- 移动端导航 ---------- */
.mobile-nav {
  border-top: 1px solid var(--c-border);
  background: var(--c-surface);
  padding: 10px 0 var(--s-4);
}

.mobile-nav .layout {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.mobile-nav a {
  padding: 12px;
  border-radius: var(--r-sm);
  color: var(--c-text-secondary);
  font-size: var(--t-md);
}

.mobile-nav a:hover,
.mobile-nav a.active {
  background: var(--c-surface-sunken);
  color: var(--c-text);
}

.mobile-nav a.active {
  font-weight: 620;
}

.mobile-search {
  margin-top: var(--s-2);
}

.mobile-search input {
  width: 100%;
  height: 44px;
  padding: 0 var(--s-4);
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface-sunken);
  color: var(--c-text);
  outline: none;
}

.drop-enter-active,
.drop-leave-active {
  transition: opacity 0.18s var(--ease), transform 0.18s var(--ease);
}

.drop-enter-from,
.drop-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

/* ---------- 主区 ---------- */
.main {
  flex: 1;
  padding: var(--s-12) 0 var(--s-20);
}

.page-enter-active {
  transition: opacity 0.26s var(--ease), transform 0.26s var(--ease-out);
}

.page-leave-active {
  transition: opacity 0.12s var(--ease);
}

.page-enter-from {
  opacity: 0;
  transform: translateY(10px);
}

.page-leave-to {
  opacity: 0;
}

/* ---------- 页脚 ---------- */
.footer {
  border-top: 1px solid var(--c-border);
  padding: var(--s-10) 0 var(--s-6);
  font-size: var(--t-sm);
  color: var(--c-text-muted);
}

.footer-inner {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-6) var(--s-12);
  justify-content: space-between;
}

.footer-brand-col {
  max-width: 320px;
}

.footer-brand {
  display: block;
  color: var(--c-text);
  font-weight: 660;
  font-size: var(--t-base);
  letter-spacing: -0.015em;
}

.footer-desc {
  margin-top: 6px;
  font-size: var(--t-sm);
  color: var(--c-text-muted);
  line-height: 1.6;
}

.footer-links {
  display: flex;
  gap: var(--s-5);
  align-items: flex-start;
  padding-top: 2px;
}

.footer-links a {
  color: var(--c-text-muted);
}

.footer-links a:hover {
  color: var(--c-brand);
}

.footer-meta {
  display: flex;
  flex-direction: column;
  gap: var(--s-2);
  align-items: flex-start;
}

.footer-meta a {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--c-text-muted);
}

.footer-meta a:hover {
  color: var(--c-brand);
}

.footer-meta svg {
  width: 15px;
  height: 15px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.footer-bottom {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2) var(--s-6);
  justify-content: space-between;
  margin-top: var(--s-8);
  padding-top: var(--s-5);
  border-top: 1px solid var(--c-border);
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

/* ---------- 回到顶部 ---------- */
.to-top {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 40;
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border: 1px solid var(--c-border);
  border-radius: var(--r-full);
  background: var(--c-surface);
  color: var(--c-text-secondary);
  cursor: pointer;
  box-shadow: var(--e);
  transition: color 0.15s var(--ease), border-color 0.15s var(--ease),
    transform 0.15s var(--ease);
}

.to-top:hover {
  color: var(--c-brand);
  border-color: var(--c-brand);
  transform: translateY(-2px);
}

.to-top svg {
  width: 18px;
  height: 18px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.9;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s var(--ease), transform 0.2s var(--ease);
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
  transform: translateY(6px) scale(0.94);
}

/* ---------- 响应式 ---------- */
@media (max-width: 900px) {
  .nav,
  .search {
    display: none;
  }

  .menu-btn {
    display: grid;
  }

  .header-inner {
    gap: var(--s-4);
  }

  .main {
    padding: var(--s-8) 0 var(--s-12);
  }

  .footer-inner {
    gap: var(--s-6);
  }
}

@media (max-width: 480px) {
  .brand-sub {
    display: none;
  }

  .to-top {
    right: 16px;
    bottom: 16px;
  }
}
</style>
