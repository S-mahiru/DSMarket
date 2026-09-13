// `defineConfig` 取自 `vitest/config` 而不是 `vite`：前者是后者的超集，多认一个 `test` 段。
// 这样测试配置与构建配置同处一文件，`@` 别名只有一处定义，不会两边分叉。
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  test: {
    // 组件要真实 DOM；jsdom 是本 REQ（§12.3 D12）三个新增 devDependencies 之一
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    // 不开 globals：测试文件显式 `import { describe, it, expect } from 'vitest'`，
    // 省得再去 tsconfig.app.json 的 `types` 里加条目
    globals: false
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/uploads': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
