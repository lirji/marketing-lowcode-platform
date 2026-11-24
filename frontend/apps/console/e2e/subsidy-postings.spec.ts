import { expect, test } from '@playwright/test'

test('funding reconciliation shows scoped postings, refunds and exact minor units', async ({ page }, info) => {
  await page.clock.setFixedTime(new Date('2026-09-17T03:00:00Z'))
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/campaigns', route => route.fulfill({ json: [{ id: 'cmp-1', name: '新人礼', objective: '首购', campaignType: 'STANDARD', status: 'ACTIVE', createdAt: '2026-09-16T15:00:00Z' }] }))
  await page.route('**/api/v1/measurements/**', route => {
    const url = new URL(route.request().url())
    const campaignId = url.searchParams.get('campaignId') ?? undefined
    return route.fulfill({ json: url.pathname.endsWith('/dashboard') ? { counts: {}, attributedRevenueMinor: 100, fundingCostMinor: 0, completeThrough: url.searchParams.get('to'), campaignId } : { points: [], campaignId } })
  })
  await page.route('**/api/v1/funding/reconciliation', route => route.fulfill({ json: { balanced: true, violations: [], ledgerMovement: 9, checkedAt: '2026-09-17T02:00:00Z' } }))
  await page.route('**/api/v1/definitions/latest?*', route => route.fulfill({
    status: 404, json: { status: 404, code: 'DEFINITION_NOT_FOUND', title: '规则不存在' },
  }))
  await page.route('**/api/v1/traces/orders/settlement-order', route => {
    expect(route.request().method()).toBe('GET')
    return route.fulfill({ json: { requestId: 'settlement-request', events: [] } })
  })
  const requests: URL[] = []
  await page.route('**/api/v1/funding/subsidy-postings?*', async route => {
    expect(route.request().method()).toBe('GET')
    const url = new URL(route.request().url())
    requests.push(url)
    const cursor = url.searchParams.get('cursor')
    const more = !cursor
    const campaignId = url.searchParams.get('campaignId')
    await route.fulfill({ json: {
      from: url.searchParams.get('from'), to: url.searchParams.get('to'), campaignId, unassigned: url.searchParams.get('unassigned') === 'true',
      hasMore: more, nextCursor: more ? 'posting-confirm' : null,
      items: [{ ledgerId: more ? 'posting-confirm' : 'posting-refund', postedAt: '2026-09-16T16:00:00Z',
        applicationId: 'application-1', campaignId, currency: 'CNY', resourceKey: 'BUDGET:PLATFORM:platform:CNY',
        operation: more ? 'CONFIRM' : 'REFUND', amountMinor: more ? '9007199254740993' : '-9007199254740992' }],
    } })
  })
  await page.goto('/operations')
  await page.getByRole('tab', { name: '资金对账' }).click()
  await page.getByRole('combobox', { name: '活动归属' }).click()
  await page.getByRole('option', { name: '仅待归属流水' }).click()
  await page.getByRole('button', { name: '查询补贴流水' }).click()
  const table = page.getByRole('table', { name: '补贴流水' })
  await expect(table.getByText('9,007,199,254,740,993', { exact: true })).toBeVisible()
  await expect(table.getByText('待归属', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '加载更多流水' }).click()
  await expect(table.getByText('退款或冲正', { exact: true })).toBeVisible()
  await expect(page.getByText(/已加载净额 CNY 1（最小货币单位）/)).toBeVisible()
  expect(requests.map(url => url.searchParams.get('unassigned'))).toEqual(['true', 'true'])
  expect(requests[1].searchParams.get('from')).toBe('2026-09-16T16:00:00.000Z')
  expect(requests[1].searchParams.get('cursor')).toBe('posting-confirm')
  expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(1)
  await page.screenshot({ path: info.outputPath('funding-postings.png'), fullPage: true })

  await page.goto('/analytics')
  await page.getByRole('combobox', { name: '活动筛选' }).click()
  await page.getByRole('option', { name: '新人礼 · cmp-1' }).click()
  await page.getByRole('link', { name: '核对补贴流水' }).click()
  await expect(table.getByText('cmp-1', { exact: true })).toBeVisible()
  expect(requests.at(-1)?.searchParams.get('campaignId')).toBe('cmp-1')
  expect(requests.at(-1)?.searchParams.get('from')).toBe('2026-09-16T16:00:00.000Z')

  await page.goto('/campaigns/cmp-1/workbench')
  await page.getByRole('link', { name: '查看本活动补贴流水' }).click()
  await expect(table.getByText('cmp-1', { exact: true })).toBeVisible()
  expect(requests.at(-1)?.searchParams.get('campaignId')).toBe('cmp-1')
  expect(requests.at(-1)?.searchParams.get('from')).toBe('2026-09-10T03:00:00.000Z')
  expect(requests.at(-1)?.searchParams.get('to')).toBe('2026-09-17T03:00:00.000Z')
  await page.goBack()

  await page.getByRole('link', { name: '按订单查营销轨迹' }).click()
  await page.getByPlaceholder('填写完整订单 ID').fill('settlement-order')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page.getByText('settlement-request', { exact: true })).toBeVisible()
})
