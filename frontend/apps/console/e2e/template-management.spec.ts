import { expect, test } from '@playwright/test'

const existing = { templateId: 'welcome-message', version: 1, channel: 'SMS', content: '原始正文 {{name}} <script>纯文本</script>', requiredVariables: ['name'], state: 'APPROVED', createdAt: '2026-11-17T00:00:00Z' }

test('template versions retain inputs on conflict and protect unsaved edits', async ({ page }, info) => {
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', route => route.fulfill({ json: new URL(route.request().url()).pathname.endsWith('/audiences/page') ? { items: [], nextCursor: null, hasMore: false, query: '' } : [] }))
  await page.route('**/api/v1/client-errors', route => route.fulfill({ status: 204 }))
  let value = existing, attempts = 0
  await page.route('**/api/v1/templates/catalog/page?**', async route => { await route.fulfill({ json: { items: [value], nextCursor: null, hasMore: false, query: new URL(route.request().url()).searchParams.get('q') ?? '' } }) })
  await page.route('**/api/v1/templates', async route => {
    if (route.request().method() === 'GET') { await route.fulfill({ json: [value] }); return }
    const body = route.request().postDataJSON(); attempts++
    expect(body.templateId).toBe(existing.templateId)
    expect(route.request().headers()['idempotency-key']).toBeTruthy()
    if (attempts === 1) { await route.fulfill({ status: 409, json: { type: 'about:blank', status: 409, title: 'Conflict', code: 'TEMPLATE_VERSION_EXISTS', detail: '模板版本已存在，原内容未被覆盖' } }); return }
    expect(body.version).toBe(3); value = { ...body, state: 'APPROVED', createdAt: existing.createdAt }
    await route.fulfill({ status: 201, json: value })
  })
  await page.goto('/assets'); await page.getByRole('tab', { name: '消息模板' }).click()
  await page.getByRole('button', { name: '查看模板 welcome-message' }).click()
  await expect(page.getByRole('dialog')).toContainText(existing.content)
  await page.getByRole('button', { name: '关闭详情' }).click()
  await page.getByRole('button', { name: '编辑模板 welcome-message 新版本' }).click()
  await page.getByLabel('模板正文').fill('新正文 {{name}}')
  await page.getByRole('button', { name: '取消编辑' }).click(); await expect(page.getByText('当前修改尚未保存。关闭后将丢失这些输入。')).toBeVisible()
  await page.getByRole('button', { name: '继续编辑' }).click(); await page.getByRole('button', { name: '保存新版本' }).click()
  await expect(page.getByText('模板保存失败', { exact: true })).toBeVisible(); await expect(page.getByLabel('模板正文')).toHaveValue('新正文 {{name}}')
  await page.screenshot({ path: info.outputPath('template-conflict.png'), fullPage: true })
  await page.getByLabel('目标版本').fill('3'); await page.getByRole('button', { name: '保存新版本' }).click()
  await expect(page.getByText('模板版本已保存', { exact: true })).toBeVisible(); await expect(page.getByRole('table', { name: '消息模板', exact: true })).toContainText('v3')
  await page.screenshot({ path: info.outputPath('template-saved.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
  await page.unrouteAll({ behavior: 'wait' })
})

test('template creation and editing persist to the actual isolated MySQL service', async ({ page }, info) => {
  const endpoint = process.env.MARKETING_LIVE_TEMPLATE_ENDPOINT
  test.skip(!endpoint, '需要已授权的隔离触达服务/MySQL；仅保存模板，不发送消息')
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  const receipts: { method: string; status: number; body: unknown }[] = [], errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url()), template = url.pathname === '/api/v1/templates' || url.pathname === '/api/v1/templates/catalog/page'
    expect(template || route.request().method() === 'GET').toBeTruthy()
    const response = await route.fetch({ url: (template ? endpoint : 'http://127.0.0.1:18080') + url.pathname + url.search, maxRetries: 0 })
    if (template) receipts.push({ method: route.request().method(), status: response.status(), body: await response.json() })
    await route.fulfill({ response })
  })
  const id = 'template-live-' + info.project.name + '-' + Date.now()
  await page.goto('/assets'); await page.getByRole('tab', { name: '消息模板' }).click()
  await page.getByRole('button', { name: '新建消息模板' }).click()
  await page.getByLabel('模板 ID').fill(id); await page.getByLabel('渠道标识').fill('SMS'); await page.getByLabel('模板正文').fill('隔离版本一 {{name}}'); await page.getByLabel('必需变量').fill('name')
  await page.getByRole('button', { name: '保存新版本' }).click(); await expect(page.getByText('模板版本已保存', { exact: true })).toBeVisible()
  await page.getByRole('searchbox', { name: '搜索消息模板' }).fill(id)
  await page.getByRole('button', { name: `编辑模板 ${id} 新版本` }).click(); await expect(page.getByLabel('目标版本')).toHaveValue('2'); await expect(page.getByLabel('模板正文')).toHaveValue('隔离版本一 {{name}}')
  await page.getByLabel('模板正文').fill('隔离版本二 {{name}}')
  await page.screenshot({ path: info.outputPath('template-live-editor.png') })
  await page.getByLabel('必需变量').scrollIntoViewIfNeeded()
  await page.screenshot({ path: info.outputPath('template-live-editor-actions.png') })
  await page.getByRole('button', { name: '保存新版本' }).click(); await expect(page.getByText('模板版本已保存', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '刷新模板' }).click(); await page.getByRole('button', { name: `查看模板 ${id}` }).click()
  await expect(page.getByRole('dialog')).toContainText('隔离版本二 {{name}}'); await expect(page.getByRole('dialog')).toContainText('v2')
  await page.screenshot({ path: info.outputPath('template-live-detail.png') })
  const posts = receipts.filter(row => row.method === 'POST'); expect(posts.map(row => row.status)).toEqual([201, 201]); expect(errors).toEqual([])
  await info.attach('actual-template-receipts', { body: JSON.stringify({ id, receipts }, null, 2), contentType: 'application/json' })
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
  await page.unrouteAll({ behavior: 'wait' })
})
