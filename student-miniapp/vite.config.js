import { defineConfig, loadEnv } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'CAMPUSAI_')

  return {
    plugins: [uni()],
    server: {
      host: '127.0.0.1',
      port: 5174,
      strictPort: true,
      proxy: {
        '^/api(?:/|$)': {
          target: env.CAMPUSAI_BACKEND_URL || 'http://127.0.0.1:8080',
          changeOrigin: true,
          rewrite: path => path.replace(/^\/api/, '')
        }
      }
    }
  }
})
