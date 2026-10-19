import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'node:path'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd())
  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, 'src')
      }
    },
    server: {
      // 5180 而不是 Vite 默认的 5173：本机同时跑多个项目，5173 经常被抢。
      port: 5180,
      // 端口被占时直接报错退出，而不是悄悄换一个 —— 否则打开的还是别的项目。
      strictPort: true,
      open: false,
      proxy: {
        // 本地开发时把 /api 代理到后端，避免跨域
        '/api': {
          target: env.VITE_API_TARGET || 'http://localhost:10730',
          changeOrigin: true
        }
      }
    },
    build: {
      outDir: 'dist',
      chunkSizeWarningLimit: 1500
    }
  }
})
