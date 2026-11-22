import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'CAMPUSAI_')
  const proxy = {
    '^/api/': {
      target: env.CAMPUSAI_BACKEND_URL || 'http://127.0.0.1:8080',
      changeOrigin: true,
      rewrite: (path) => path.slice('/api'.length),
    },
  }

  return {
    plugins: [vue()],
    server: { host: '127.0.0.1', port: 5173, proxy },
    preview: { host: '127.0.0.1', proxy },
  }
})
