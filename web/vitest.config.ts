import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

/** Vitest 仅收集组件单测，端到端场景由 Playwright 独立执行。 */
export default defineConfig({
  plugins: [react()],
  test: {
    include: ['src/**/*.test.{ts,tsx}'],
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
})
