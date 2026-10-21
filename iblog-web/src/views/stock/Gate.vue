<template>
  <div class="stock-gate">
    <router-view v-if="authenticated" />

    <!-- 首次校验：轻量占位，避免先闪一下「未登录」再跳成行情 -->
    <div v-else-if="!checked" class="layout stock-gate__state" role="status" aria-live="polite">
      <span class="stock-gate__spinner" aria-hidden="true" />
      <p class="stock-gate__checking">正在校验登录状态…</p>
    </div>

    <!-- 未登录：锁定卡片。不做重定向，用户能清楚知道为什么进不去 -->
    <div v-else class="layout stock-gate__state">
      <section class="stock-gate__card" aria-labelledby="stock-gate-title">
        <span class="stock-gate__icon" aria-hidden="true">
          <svg viewBox="0 0 24 24">
            <rect x="4.5" y="10.5" width="15" height="10" rx="2.5" />
            <path d="M8 10.5V7.8a4 4 0 0 1 8 0v2.7" />
            <path d="M12 14.6v2.4" />
          </svg>
        </span>
        <h1 id="stock-gate-title" class="stock-gate__title">{{ gateTitle }}</h1>
        <p class="stock-gate__desc">{{ gateDesc }}</p>
        <router-link to="/" class="btn stock-gate__action">返回首页</router-link>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useAuth } from '@/composables/useAuth'

// 行情与模拟盘共用这道门禁，锁定卡文案由路由 meta 提供，缺省保持行情文案
const route = useRoute()
const { authenticated, checked, ensureAuth } = useAuth()

const gateTitle = computed(
  () => (route.meta.gateTitle as string | undefined) ?? '行情仅对已登录用户开放'
)
const gateDesc = computed(
  () =>
    (route.meta.gateDesc as string | undefined) ??
    '请先登录管理后台，再通过后台顶部的「行情」入口进入。同一浏览器登录过后台后，行情会自动解锁。'
)

onMounted(() => {
  // 路由守卫已校验过时直接命中缓存；这里兜底直接打开 /stock、/paper 深链的情况
  void ensureAuth()
})
</script>

<style scoped>
.stock-gate {
  min-height: 60vh;
}

.stock-gate__state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 56vh;
  text-align: center;
}

/* ---------- 校验中 ---------- */
.stock-gate__spinner {
  width: 22px;
  height: 22px;
  border: 2px solid var(--c-border-strong);
  border-top-color: var(--c-brand);
  border-radius: 50%;
  animation: stock-gate-spin 0.8s linear infinite;
}

.stock-gate__checking {
  margin-top: var(--s-3);
  color: var(--c-text-muted);
  font-size: var(--t-sm);
}

@keyframes stock-gate-spin {
  to {
    transform: rotate(360deg);
  }
}

/* ---------- 锁定卡片 ---------- */
.stock-gate__card {
  width: 100%;
  max-width: 520px;
  padding: var(--s-10) var(--s-8);
  border: 1px solid var(--c-border);
  border-radius: var(--r-lg);
  background: var(--c-surface);
  box-shadow: var(--e-lg);
}

.stock-gate__icon {
  display: inline-grid;
  place-items: center;
  width: 48px;
  height: 48px;
  margin-bottom: var(--s-5);
  border-radius: var(--r-full);
  background: var(--c-brand-soft);
  color: var(--c-brand);
}

.stock-gate__icon svg {
  width: 24px;
  height: 24px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.stock-gate__title {
  font-size: var(--t-xl);
  font-weight: 680;
  letter-spacing: -0.02em;
  color: var(--c-text);
}

.stock-gate__desc {
  max-width: 30em;
  margin: var(--s-3) auto 0;
  color: var(--c-text-muted);
  font-size: var(--t-sm);
  line-height: 1.8;
}

.stock-gate__action {
  margin-top: var(--s-6);
}

@media (max-width: 600px) {
  .stock-gate__state {
    min-height: 48vh;
  }

  .stock-gate__card {
    padding: var(--s-8) var(--s-5);
  }
}

/* 尊重系统的减少动效偏好：别为了一个加载指示器跟用户对着干 */
@media (prefers-reduced-motion: reduce) {
  .stock-gate__spinner {
    animation-duration: 2.4s;
  }
}
</style>
