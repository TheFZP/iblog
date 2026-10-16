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
      port: 5174,
      open: false,
      watch: {
        // 原子写文件时编辑器/工具会生成临时目录，chokidar 监听它们会在
        // Windows 上抛 EBUSY 并把 dev server 整个带崩，这里直接忽略。
        ignored: ['**/.*.tmpdir/**', '**/*.tmpdir/**']
      },
      proxy: {
        '/api': {
          target: env.VITE_API_TARGET || 'http://localhost:8080',
          changeOrigin: true
        }
      }
    },
    build: {
      outDir: 'dist'
    }
  }
})
