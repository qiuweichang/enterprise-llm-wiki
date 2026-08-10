import { defineConfig, devices } from '@playwright/test'

/** 可选复用机器已安装浏览器，适配离线或浏览器下载源不可达的企业环境。 */
const browserExecutable = process.env.LLM_WIKI_BROWSER_EXECUTABLE

/** 浏览器端到端配置；后端与 Python 服务由外层联调脚本启动。 */
export default defineConfig({
  testDir: './e2e',
  timeout: 120_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://127.0.0.1:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    launchOptions: browserExecutable ? { executablePath: browserExecutable } : undefined,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: true,
    timeout: 60_000,
  },
})
