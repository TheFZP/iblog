<template>
  <el-container class="layout">
    <el-aside width="210px" class="aside">
      <div class="logo">iblog</div>
      <el-menu :default-active="activeMenu" router class="menu">
        <el-menu-item index="/dashboard">
          <el-icon><DataLine /></el-icon>
          <span>仪表盘</span>
        </el-menu-item>
        <el-menu-item index="/articles">
          <el-icon><Document /></el-icon>
          <span>文章管理</span>
        </el-menu-item>
        <el-menu-item index="/taxonomy">
          <el-icon><Collection /></el-icon>
          <span>分类与标签</span>
        </el-menu-item>
        <el-menu-item index="/comments">
          <el-icon><ChatDotRound /></el-icon>
          <span>评论管理</span>
        </el-menu-item>
        <el-menu-item index="/foods">
          <el-icon><Food /></el-icon>
          <span>美食管理</span>
        </el-menu-item>
        <el-menu-item index="/settings">
          <el-icon><Setting /></el-icon>
          <span>站点设置</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="crumb">{{ pageTitle }}</div>
        <div class="header-right">
          <el-button link type="primary" @click="openStock">行情</el-button>
          <el-button link type="primary" @click="openWeb">查看官网</el-button>
          <el-dropdown @command="onCommand">
            <span class="user">
              {{ userStore.nickname || 'admin' }}
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowDown,
  ChatDotRound,
  Collection,
  DataLine,
  Document,
  Food,
  Setting
} from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { getStockTicket } from '@/api/auth'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const activeMenu = computed(() => {
  const path = route.path
  if (path.startsWith('/articles')) {
    return '/articles'
  }
  return path
})

const pageTitle = computed(() => (route.meta.title as string) || '')

const webUrl = import.meta.env.VITE_WEB_URL || '/'

function openWeb() {
  window.open(webUrl, '_blank')
}

/**
 * 从后台直接打开官网「行情」。
 *
 * 官网与后台同主机（不同端口）时，登录 Cookie 天然共享，这里走一次性票据也无害；
 * 两者是不同网址时，票据就是免二次登录的关键。
 * 注意 VITE_WEB_URL 必须是官网完整地址，相对路径 '/' 无法拼出跨站链接。
 */
async function openStock() {
  if (!/^https?:\/\//i.test(webUrl)) {
    ElMessage.warning('请先把 iblog-admin/.env.production 里的 VITE_WEB_URL 配成官网完整地址（如 https://www.example.com），再从后台打开行情')
    return
  }
  // 先同步开窗口、再异步取票据：await 之后浏览器会丢失“用户手势”，
  // 届时再 window.open 容易被当成弹窗拦截。
  const stockWindow = window.open('', '_blank')
  if (!stockWindow) {
    ElMessage.warning('浏览器拦截了新窗口，请允许本站弹窗后重试')
    return
  }
  try {
    const { ticket } = await getStockTicket()
    const base = webUrl.replace(/\/+$/, '')
    stockWindow.location.replace(`${base}/stock?ticket=${encodeURIComponent(ticket)}`)
  } catch {
    stockWindow.close()
    // 失败提示由 request.ts 拦截器统一处理
  }
}

async function onCommand(command: string) {
  if (command !== 'logout') {
    return
  }
  await ElMessageBox.confirm('确定要退出登录吗？', '提示', { type: 'warning' })
  await userStore.logout()
  router.push('/login')
}

onMounted(() => {
  if (!userStore.nickname) {
    userStore.fetchUserInfo().catch(() => undefined)
  }
})
</script>

<style scoped>
.layout {
  height: 100vh;
}

.aside {
  background-color: #001529;
  overflow: hidden;
}

.logo {
  height: 60px;
  line-height: 60px;
  text-align: center;
  color: #fff;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: 2px;
}

.menu {
  border-right: none;
  background-color: #001529;
}

.menu :deep(.el-menu-item) {
  color: #c9d1d9;
}

.menu :deep(.el-menu-item.is-active) {
  color: #fff;
  background-color: #1890ff;
}

.menu :deep(.el-menu-item:hover) {
  background-color: #12263f;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
}

.crumb {
  font-size: 16px;
  font-weight: 500;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 18px;
}

.user {
  display: flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  outline: none;
}

.main {
  background: #f5f7fa;
  padding: 16px;
}
</style>
