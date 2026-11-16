import { expect, test } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true }
  })
  await page.route('**/api/v1/**', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/client-error', (route) => route.fulfill({ status: 204 }))
})

test('live trace states never substitute demo results and preserve the submitted ID', async ({ page }, info) => {
  let release!: () => void
  const waiting = new Promise<void>((done) => { release = done })
  await page.route('**/api/v1/traces/requests/failure-request', async (route) => {
    await waiting
    await route.fulfill({ status: 503, json: { type: 'about:blank', title: 'Unavailable', status: 503, detail: '轨迹服务暂不可用' } })
  })
  await page.route('**/api/v1/traces/requests/empty-request', (route) => route.fulfill({ status: 204 }))
  await page.route('**/api/v1/traces/requests/live-request', (route) => route.fulfill({ json: {
    requestId: 'live-request', traceId: 'trace-live', maskedSubject: 'masked-live',
    generation: 12, durationMicros: 1500, candidates: { accepted: ['live-benefit'] }, events: [],
  } }))
  await page.goto('/operations')
  const input = page.getByPlaceholder('填写完整请求 ID')
  const search = page.getByRole('button', { name: '查询', exact: true })
  await input.fill('failure-request')
  await search.click()
  await expect(page.getByText('正在查询轨迹', { exact: true })).toBeVisible()
  await expect(page.getByText('候选与裁决', { exact: true })).toHaveCount(0)
  await page.screenshot({ path: info.outputPath('trace-pending.png'), fullPage: true })
  release()
  await expect(page.getByText('轨迹查询失败', { exact: true })).toBeVisible()
  await expect(page.getByText('CATEGORY_MATCH', { exact: true })).toHaveCount(0)
  await expect(page.getByText('演示', { exact: true })).toHaveCount(0)
  await page.screenshot({ path: info.outputPath('trace-error.png'), fullPage: true })
  await input.fill('empty-request')
  await search.click()
  await expect(page.getByText('未找到轨迹', { exact: true })).toBeVisible()
  await page.screenshot({ path: info.outputPath('trace-empty.png'), fullPage: true })
  await input.fill('live-request')
  await search.click()
  await expect(page.locator('code').filter({ hasText: /^live-request$/ })).toBeVisible()
  await expect(page.getByText('没有逐步事件', { exact: true })).toBeVisible()
  await input.fill('unsubmitted-id')
  await expect(page.locator('code').filter({ hasText: /^live-request$/ })).toBeVisible()
  await expect(page.locator('code').filter({ hasText: /^unsubmitted-id$/ })).toHaveCount(0)
  await expect(page.getByText('API', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(1)
  await page.screenshot({ path: info.outputPath('trace-success.png'), fullPage: true })
})

test('DEV logout and login reload the selected tenant without cached records', async ({ page }, info) => {
  const requestedTenants: string[] = []
  await page.route('**/api/v1/campaigns', async (route) => {
    const tenant = route.request().headers()['x-dev-tenant-id']
    requestedTenants.push(tenant)
    await route.fulfill({ json: [{ id: `campaign-${tenant}`, name: `活动-${tenant}`, objective: '验证身份范围',
      status: 'DRAFT', organizationId: tenant, createdAt: '2026-11-16T00:00:00Z' }] })
  })
  await page.goto('/campaigns')
  await expect(page.getByRole('cell', { name: '活动-retail-cn', exact: true })).toBeVisible()
  await page.getByTitle('账户', { exact: true }).click()
  await page.getByRole('button', { name: '退出', exact: true }).click()
  await expect(page.getByRole('heading', { name: '进入本地开发模式' })).toBeVisible()
  await page.getByLabel('货主业务租户').fill('tenant-b')
  await page.getByRole('button', { name: '进入本地开发模式', exact: true }).click()
  // 登录后的默认路径由现有合同决定，再从同一个 SPA 导航进入活动列表。
  if (!page.url().includes('/campaigns')) {
    const nav = page.getByRole('link', { name: '计划与活动', exact: true })
    if (!await nav.isVisible()) await page.getByRole('button', { name: '打开菜单' }).click()
    await nav.click()
  }
  await expect(page.getByRole('cell', { name: '活动-tenant-b', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: '活动-retail-cn', exact: true })).toHaveCount(0)
  expect(requestedTenants).toContain('tenant-b')
  await page.screenshot({ path: info.outputPath('tenant-switched.png'), fullPage: true })
})
