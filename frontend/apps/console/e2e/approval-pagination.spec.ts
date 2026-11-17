import { expect, test } from '@playwright/test'

const approval = (id: string, status = 'OPEN') => ({ caseId: id, definitionId: `definition-${id}`, definitionVersion: 1, submittedBy: 'author', requiredRoles: ['BUSINESS'], approvals: {}, status, updatedAt: '2026-11-17T00:00:00Z' })
test('approval pagination preserves loaded rows during failure and filters on the server', async ({ page }, info) => {
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', route => route.fulfill({ json: [] }))
  let fail = true
  await page.route('**/api/v1/approvals/page?**', async route => {
    const url = new URL(route.request().url()), status = url.searchParams.get('status'), cursor = url.searchParams.get('cursor')
    if (cursor && fail) { await route.fulfill({ status: 503, json: { type: 'about:blank', title: 'Unavailable', status: 503, detail: '分页故障验证' } }); return }
    const items = [approval(status === 'APPROVED' ? 'approved-old' : cursor ? 'case-older' : 'case-newer', status ?? 'OPEN')]
    const hasMore = status === 'OPEN' && !cursor
    await route.fulfill({ json: { items, hasMore, nextCursor: hasMore ? items[0].caseId : null, status } })
  })
  await page.goto('/governance'); await expect(page.getByText('已加载 1 项，还有更多')).toBeVisible()
  await page.getByRole('button', { name: '加载更多审核单' }).click(); await expect(page.getByText('后续审核单加载失败')).toBeVisible({ timeout: 20000 })
  await expect(page.getByRole('button', { name: '查看审核：case-newer' })).toBeVisible()
  await page.screenshot({ path: info.outputPath('approval-page-failure.png') })
  fail = false; await page.getByRole('button', { name: '重试加载更多审核单' }).click(); await expect(page.getByText('已加载 2 项', { exact: true })).toBeVisible()
  await page.getByRole('combobox', { name: '审核状态' }).click(); await page.getByRole('option', { name: '已批准', exact: true }).click()
  await expect(page.getByRole('button', { name: '查看审核：approved-old' })).toBeVisible(); await expect(page.getByRole('button', { name: '查看审核：case-newer' })).toHaveCount(0)
  await page.screenshot({ path: info.outputPath('approval-page-filter.png') })
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
})

test('approval and release selection traverse the actual isolated MySQL service', async ({ page }, info) => {
  const endpoint = process.env.MARKETING_LIVE_APPROVAL_ENDPOINT
  test.skip(!endpoint, '需要已准备的独占审批测试库和服务；浏览器仅GET')
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  const receipts: { path: string; status: number; body: unknown }[] = [], errors: string[] = []
  page.on('pageerror', e => errors.push(e.message))
  await page.route('**/api/v1/**', async route => {
    expect(route.request().method()).toBe('GET')
    const url = new URL(route.request().url()), isolated = url.pathname.startsWith('/api/v1/approvals/') || url.pathname.startsWith('/api/v1/definitions/')
    const response = await route.fetch({ url: (isolated ? endpoint : 'http://127.0.0.1:18080') + url.pathname + url.search, maxRetries: 0 })
    if (isolated) receipts.push({ path: url.pathname + url.search, status: response.status(), body: await response.json() })
    await route.fulfill({ response })
  })
  await page.goto('/governance'); await expect(page.getByText('已加载 25 项，还有更多')).toBeVisible({ timeout: 20000 })
  await page.screenshot({ path: info.outputPath('approval-live-first-page.png') })
  await page.getByRole('button', { name: '加载更多审核单' }).click(); await expect(page.getByText('已加载 50 项，还有更多')).toBeVisible()
  await page.getByRole('button', { name: '加载更多审核单' }).click(); await expect(page.getByText('已加载 55 项', { exact: true })).toBeVisible()
  const table = page.getByRole('table', { name: '待审核变更' }); await expect(table.getByRole('row')).toHaveCount(56)
  await expect(table).not.toContainText('approval-live-hidden')
  await page.getByRole('button', { name: '查看审核：approval-live-open-000' }).click(); await expect(page.getByRole('dialog')).toContainText('approval-live-open')
  await page.getByLabel('审核意见（必填，写入审计，最多 128 字）').fill('仅核对分页，不提交审核')
  await page.screenshot({ path: info.outputPath('approval-live-old-detail.png') }); await page.getByRole('button', { name: '关闭详情' }).click()
  await page.goto('/releases'); await page.getByRole('button', { name: '编译并暂存', exact: true }).first().click()
  const wizard = page.getByRole('dialog'); await expect(wizard.getByText('已加载 25 条批准单，还有更多')).toBeVisible()
  for (const total of [50, 75, 100, 105]) {
    await wizard.getByRole('button', { name: '加载更多批准单' }).click(); await expect(wizard.getByText(`已加载 ${total} 条批准单${total < 105 ? '，还有更多' : ''}`, { exact: true })).toBeVisible()
  }
  await wizard.getByRole('combobox', { name: '批准单 / 冻结版本' }).click()
  await page.getByRole('option', { name: 'approval-live-approved · v1 · approval-live-approved-000', exact: true }).click()
  await expect(wizard.getByText('OFFER_DECISION_DAG · decision runtime')).toBeVisible()
  await wizard.getByRole('combobox', { name: '批准单 / 冻结版本' }).scrollIntoViewIfNeeded(); await page.screenshot({ path: info.outputPath('approval-live-older-release-choice.png') })
  expect(receipts.every(row => row.status === 200)).toBe(true); expect(errors).toEqual([])
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
  await info.attach('actual-approval-receipts', { body: JSON.stringify(receipts), contentType: 'application/json' })
  await page.unrouteAll({ behavior: 'wait' })
})
