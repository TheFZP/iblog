import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { consumeTicket, ensureAuth } from '@/composables/useAuth'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: () => import('@/layout/AppLayout.vue'),
    children: [
      {
        path: '',
        name: 'Home',
        component: () => import('@/views/home/index.vue'),
        meta: { title: '首页' }
      },
      {
        path: 'article/:id',
        name: 'ArticleDetail',
        component: () => import('@/views/article/detail.vue'),
        meta: { title: '文章详情' }
      },
      {
        path: 'list',
        name: 'ArticleList',
        component: () => import('@/views/list/index.vue'),
        meta: { title: '文章列表' }
      },
      {
        path: 'archive',
        name: 'Archive',
        component: () => import('@/views/archive/index.vue'),
        meta: { title: '归档' }
      },
      {
        path: 'food',
        name: 'Food',
        component: () => import('@/views/food/index.vue'),
        meta: { title: '美食' }
      },
      {
        // 行情页外层加一道门禁：未登录时 Gate 显示锁定卡片，登录后才渲染真正的页面
        path: 'stock',
        component: () => import('@/views/stock/Gate.vue'),
        children: [
          {
            path: '',
            name: 'Stock',
            component: () => import('@/views/stock/index.vue'),
            meta: { title: '行情' }
          },
          {
            path: ':code',
            name: 'StockDetail',
            component: () => import('@/views/stock/detail.vue'),
            meta: { title: '个股详情' }
          }
        ]
      },
      {
        path: 'about',
        name: 'About',
        component: () => import('@/views/about/index.vue'),
        meta: { title: '关于' }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  }
})

const SITE_TITLE = 'iblog'

/**
 * 登录态校验。
 *
 * <p>1. 地址栏带 ticket 时先消费：成功后后端会 Set-Cookie，紧接着把 ticket 从 query 摘掉
 * （replace 替换历史记录），既避免票据留在地址栏被刷新/分享，也避免守卫反复消费造成循环。</p>
 * <p>2. 其余情况确认一次登录态即可（已缓存时无网络请求，网络异常也按未登录继续）。</p>
 * <p>未登录不在这里重定向：「行情」由 Gate 显示锁定卡片，其他页面不受影响。</p>
 */
router.beforeEach(async (to) => {
  const rawTicket = to.query.ticket
  const ticket = Array.isArray(rawTicket) ? rawTicket[0] : rawTicket

  if (typeof ticket === 'string' && ticket.trim()) {
    const ok = await consumeTicket(ticket.trim())
    if (!ok) {
      // 票据无效会走业务码 401，触发全局未授权重置。但失效的票据不代表 Cookie 会话失效，
      // 这里强制复核一次，避免把本来还有效的登录态误清掉（失败也不影响下面的跳转）。
      await ensureAuth(true)
    }
    const query = Object.fromEntries(Object.entries(to.query).filter(([key]) => key !== 'ticket'))
    return { path: to.path, query, hash: to.hash, replace: true }
  }

  // 行情页门禁需要拿到确定的登录态，等这次校验完成再渲染；
  // 其他页面不阻塞首屏——导航栏的「行情」入口会在状态确认后自动出现。
  if (to.path === '/stock' || to.path.startsWith('/stock/')) {
    await ensureAuth()
  } else {
    void ensureAuth()
  }
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} - ${SITE_TITLE}` : SITE_TITLE
})

export default router
