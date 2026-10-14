import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { getToken } from '@/api/request'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/',
    component: () => import('@/layout/index.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '仪表盘' }
      },
      {
        path: 'articles',
        name: 'ArticleList',
        component: () => import('@/views/article/list.vue'),
        meta: { title: '文章管理' }
      },
      {
        path: 'articles/create',
        name: 'ArticleCreate',
        component: () => import('@/views/article/edit.vue'),
        meta: { title: '写文章' }
      },
      {
        path: 'articles/edit/:id',
        name: 'ArticleEdit',
        component: () => import('@/views/article/edit.vue'),
        meta: { title: '编辑文章' }
      },
      {
        path: 'taxonomy',
        name: 'Taxonomy',
        component: () => import('@/views/taxonomy/index.vue'),
        meta: { title: '分类与标签' }
      },
      {
        path: 'comments',
        name: 'CommentList',
        component: () => import('@/views/comment/index.vue'),
        meta: { title: '评论管理' }
      },
      {
        path: 'settings',
        name: 'Setting',
        component: () => import('@/views/setting/index.vue'),
        meta: { title: '站点设置' }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/dashboard'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  document.title = to.meta.title ? `${to.meta.title as string} - iblog 管理后台` : 'iblog 管理后台'

  const token = getToken()
  if (to.path === '/login') {
    return token ? { path: '/dashboard' } : true
  }
  if (!token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
