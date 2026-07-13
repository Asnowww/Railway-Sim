import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  base: './',
  plugins: [vue()],
  server: {
    port: 5500,
    proxy: {
      '/api': {
        target: process.env.VITE_BACKEND_TARGET ?? 'http://localhost:18080',
        changeOrigin: true
      },
      '/ws': {
        target: process.env.VITE_WS_TARGET ?? 'ws://localhost:18080',
        ws: true
      }
    }
  }
})
