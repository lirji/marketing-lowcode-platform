import { expect, test } from '@playwright/test'

test('dashboard shows stale data age and suppresses unverified amounts without live-health claims', async ({ page }, info) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    if (path.endsWith('/measurements/dashboard')) { await route.fulfill({ json: { counts: {}, attributedRevenueMinor: 1, fundingCostMinor: 0, roi: '0', completeThrough: '2026-09-16T15:28:22Z', lag: { seconds: 5337477 } } }); return }
    if (path.endsWith('/measurements/series')) { await route.fulfill({ json: { points: [{ at: '2026-11-17T01:00:00Z', revenueMinor: 0 }, { at: '2026-11-17T02:00:00Z', revenueMinor: 100 }] } }); return }
    if (path.endsWith('/releases/page')) { await route.fulfill({ json: { items: [], nextCursor: null, hasMore: false, state: url.searchParams.get('state') } }); return }
    if (path.endsWith('/approvals/page')) { await route.fulfill({ json: { items: [], nextCursor: null, hasMore: false, status: url.searchParams.get('status') } }); return }
    await route.fulfill({ json: [] })
  })
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '查看当前运营数据' })).toBeVisible()
  await expect(page.getByText('61 天 18 小时', { exact: true })).toBeVisible()
  await expect(page.getByText('今日数据未就绪', { exact: true })).toBeVisible()
  await expect(page.getByText('¥0.01', { exact: true })).toHaveCount(0)
  await expect(page.getByText('已加载活动', { exact: true })).toBeVisible()
  await expect(page.getByText(/在线活动|增长脉搏稳定|实时经营/)).toHaveCount(0)
  await expect(page.getByLabel('今日小时归因收入')).toHaveCount(0)
  await page.screenshot({ path: info.outputPath('dashboard-data-age.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
  expect(errors).toEqual([])
})
