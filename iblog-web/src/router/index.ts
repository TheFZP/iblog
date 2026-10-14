import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

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

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} - ${SITE_TITLE}` : SITE_TITLE
})

export default router
