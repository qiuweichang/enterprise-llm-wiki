import { expect, test } from '@playwright/test'

/**
 * 模拟真实企业成员完成登录、资料编译、页面更新审核、查询和 Obsidian 导出。
 */
test('enterprise wiki contribution and review flow', async ({ page }) => {
  test.setTimeout(150_000)
  const password = process.env.LLM_WIKI_E2E_PASSWORD ?? '1'
  const title = `浏览器联调知识 ${Date.now()}`

  await page.goto('/login')
  await page.getByLabel('账号').fill(process.env.LLM_WIKI_E2E_EMAIL ?? '1')
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '登录工作空间' }).click()
  await expect(page.getByRole('heading', { name: /早上好/ })).toBeVisible()

  await page.getByRole('link', { name: '持续优化与 MCP' }).click()
  await expect(page.getByRole('heading', { name: '大模型配置' })).toBeVisible()
  const evolutionSwitch = page.getByLabel('AI 持续优化')
  await evolutionSwitch.check()
  await expect(evolutionSwitch).toBeChecked()
  await page.getByRole('button', { name: '立即运行一次' }).click()
  await expect(page.getByText(/手动优化/).first()).toBeVisible()
  await evolutionSwitch.uncheck()
  await expect(evolutionSwitch).not.toBeChecked()

  await page.getByRole('link', { name: '工作台' }).click()

  await page.getByRole('button', { name: '添加来源' }).first().click()
  await page.getByLabel('来源标题').fill(title)
  await page.getByLabel('原始文本').fill(`# ${title}\n\n这是浏览器端到端测试创建的可追溯知识。\n\n## 规则\n\n已有页面更新必须经过审核。`)
  await page.getByRole('button', { name: '进入编译队列' }).click()
  await expect(page.getByRole('heading', { name: '后台任务已创建' })).toBeVisible()
  await page.getByRole('button', { name: '查看处理状态' }).click()
  await expect(page.getByText(title).first()).toBeVisible()

  await expect.poll(async () => {
    await page.getByLabel('刷新').click()
    return page.locator('.source-list article').filter({ hasText: title }).textContent()
  }, { timeout: 45_000 }).toContain('SUCCEEDED')

  await page.getByRole('link', { name: '知识库' }).click()
  await page.getByPlaceholder('搜索已发布页面').fill(title)
  await page.getByRole('button', { name: new RegExp(title) }).click()
  await expect(page.getByRole('heading', { name: title, level: 1 })).toBeVisible()
  await page.getByRole('button', { name: '提出修改' }).click()
  const editor = page.getByLabel('Markdown 正文')
  await editor.fill(`${await editor.inputValue()}\n\n## 审核补充\n\n这段更新不能直接发布。`)
  await page.getByRole('button', { name: '提交审核' }).click()
  await expect(page.getByRole('heading', { name: '已有知识更新已提交审核' })).toBeVisible()
  await page.getByRole('button', { name: '查看审核单' }).click()
  await expect(page.getByText('Markdown 行差异')).toBeVisible()
  await page.getByRole('button', { name: '批准并发布' }).click()
  await expect(page.getByRole('heading', { name: '变更已审核并发布' })).toBeVisible()

  await page.getByRole('link', { name: '问 Wiki' }).click()
  await page.getByPlaceholder('输入问题…').fill(`请总结 ${title}`)
  await page.getByLabel('发送').click()
  await expect(page.getByText('Wiki 助手', { exact: true }).last()).toBeVisible()
  await expect(page.locator('.inline-citations button').first()).toBeVisible()

  await page.getByRole('link', { name: '空间设置' }).click()
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出 Obsidian Vault' }).click()
  const download = await downloadPromise
  expect(download.suggestedFilename()).toBe('llm-wiki-obsidian.zip')
})
