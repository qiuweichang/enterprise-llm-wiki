import { expect, test } from '@playwright/test'
import path from 'node:path'

/**
 * 验证 AI 行业基线在知识库、图谱和独立定时任务页面中的桌面端呈现与关键交互。
 */
test('AI industry baseline and scheduled task desktop flow', async ({ page }) => {
  const consoleErrors: string[] = []
  await page.setViewportSize({ width: 1920, height: 1080 })
  await page.goto('/login')
  await page.getByLabel('账号').fill('1')
  await page.getByLabel('密码').fill('1')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/dashboard/)
  page.on('console', (message) => {
    const text = message.text()
    // Playwright 截图期间 Chromium 可能挂起 Vite 的 HMR WebSocket；这不属于应用运行时错误。
    if (message.type() === 'error' && !text.includes("WebSocket connection to 'ws://127.0.0.1:5173/' failed")) {
      consoleErrors.push(text)
    }
  })

  await page.goto('/knowledge')
  await expect(page.getByRole('heading', { name: '知识库', exact: true })).toBeVisible()
  await page.getByPlaceholder('搜索已发布页面').fill('全球主流 AI 大模型产业')
  await page.getByRole('button', { name: /^全球主流 AI 大模型产业 综合/ }).click()
  await expect(page.getByRole('heading', { name: '全球主流 AI 大模型产业', level: 1 })).toBeVisible()
  await expect(page.getByText('OpenAI', { exact: true }).first()).toBeVisible()
  await page.screenshot({ path: path.join(process.env.TEMP ?? '.', 'llm-wiki-ai-industry-knowledge.png') })

  await page.goto('/graph')
  await expect(page.getByRole('heading', { name: '知识图谱' })).toBeVisible()
  await expect(page.getByText('72 节点 · 171 关系')).toBeVisible()
  await page.getByPlaceholder('搜索知识节点').fill('SeedRealtime')
  await page.getByRole('button', { name: '打开 SeedRealtime', exact: true }).click()
  await expect(page.getByRole('heading', { name: '关联知识' })).toBeVisible()
  await expect(page.getByText('字节跳动 Seed', { exact: true }).first()).toBeVisible()
  await page.screenshot({ path: path.join(process.env.TEMP ?? '.', 'llm-wiki-ai-industry-graph.png') })

  await page.goto('/ai-tasks')
  await expect(page.getByRole('heading', { name: 'AI 定时任务', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '新建任务' }).click()
  await expect(page.getByLabel('任务类型')).toHaveValue('AI_VENDOR_NEWS')
  await expect(page.getByLabel('资讯范围')).toHaveValue('31')
  await expect(page.getByLabel('执行时间')).toHaveValue('12:00')
  await page.getByRole('button', { name: '取消' }).click()
  await expect(page.getByText('成功采集 11 个官方页面 · 失败 0 个').first()).toBeVisible()
  await page.screenshot({ path: path.join(process.env.TEMP ?? '.', 'llm-wiki-ai-industry-tasks.png') })

  expect(consoleErrors).toEqual([])
})
