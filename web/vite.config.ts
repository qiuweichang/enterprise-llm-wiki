import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/** Vite 开发配置：API 与 MCP 统一代理到 Java 服务，保持 Cookie 同源语义。 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': 'http://127.0.0.1:8123',
      '/mcp': 'http://127.0.0.1:8123',
      '/actuator': 'http://127.0.0.1:8123',
    },
  },
})
