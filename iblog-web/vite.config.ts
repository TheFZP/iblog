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
      // 5181 而不是 Vite 默认的 5173：本机同时跑多个项目，5173 经常被抢。
      port: 5181,
      // 端口被占时直接报错退出，而不是悄悄换一个 —— 否则打开的还是别的项目。
      strictPort: true,
      open: false,
      watch: {
        // 原子写文件时编辑器/工具会生成临时目录，chokidar 监听它们会在
        // Windows 上抛 EBUSY 并把 dev server 整个带崩，这里直接忽略。
        ignored: ['**/.*.tmpdir/**', '**/*.tmpdir/**']
      },
      proxy: {
        '/api': {
          target: env.VITE_API_TARGET || 'http://localhost:10730',
          changeOrigin: true
        }
      }
    },
    build: {
      outDir: 'dist'
    }
  }
})
