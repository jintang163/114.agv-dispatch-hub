import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      output: {
        manualChunks: {
          echarts: ['echarts'],
          mqtt: ['mqtt'],
          vendor: ['vue', 'vue-router', 'axios']
        }
      }
    }
  },
  server: {
    host: true,
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/mqtt': {
        target: 'ws://localhost:8083',
        ws: true,
        rewrite: (p) => p.replace(/^\/mqtt/, '/mqtt')
      }
    }
  }
})
