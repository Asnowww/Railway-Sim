import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// 代理目标解析顺序：shell 环境变量 > .env.local / .env（VITE_ 前缀） > 默认 8080。
// 本机 8080 被其他项目占用时，在 frontend/.env.local 写入：
//   VITE_BACKEND_TARGET=http://localhost:18080
//   VITE_WS_TARGET=ws://localhost:18080
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, __dirname, '')
  const backendTarget = process.env.VITE_BACKEND_TARGET ?? env.VITE_BACKEND_TARGET ?? 'http://localhost:8080'
  const wsTarget = process.env.VITE_WS_TARGET ?? env.VITE_WS_TARGET ?? backendTarget.replace(/^http/, 'ws')

  return {
    base: './',
    plugins: [vue()],
    build: {
      rollupOptions: {
        output: {
          manualChunks: { echarts: ['echarts'] }
        }
      }
    },
    server: {
      port: 5500,
      proxy: {
        '/api': {
          target: backendTarget,
          changeOrigin: true
        },
        '/ws': {
          target: wsTarget,
          ws: true
        }
      }
    }
  }
})
