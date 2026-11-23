import { expect, test, type Page } from '@playwright/test'

async function chooseDay(page: Page, label: string, day: number) {
  await page.getByRole('button', { name: label, exact: true }).click()
  const picker = page.getByRole('dialog', { name: `选择${label}` })
  await picker.locator('.rdp-day_button').filter({ hasText: new RegExp(`^${day}$`) }).click()
  await picker.getByRole('button', { name: '确认时间' }).click()
}

test('compares a historic activity with the ledger and withholds incompatible differences', async ({ page }, info) => {
  await page.clock.setFixedTime(new Date('2026-09-23T03:00:00Z'))
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/campaigns', route => route.fulfill({ json: [{ id: 'cmp-1', name: '家电主会场', objective: '转化', status: 'ACTIVE', createdAt: '2026-09-01T00:00:00Z' }] }))
  const measurements: URL[] = []
  await page.route('**/api/v1/measurements/**', route => {
    const url = new URL(route.request().url())
    measurements.push(url)
    const campaignId = url.searchParams.get('campaignId') ?? undefined
    return route.fulfill({ json: url.pathname.endsWith('/dashboard') ? { counts: {}, attributedRevenueMinor: 57_990, fundingCostMinor: 16_000, completeThrough: url.searchParams.get('to'), campaignId } : { points: [], campaignId } })
  })
  const ledgerReads: URL[] = []
  let tenantWide = true
  let includePending = false
  await page.route('**/api/v1/funding/subsidy-postings/summary?*', async route => {
    expect(route.request().method()).toBe('GET')
    const url = new URL(route.request().url())
    ledgerReads.push(url)
    const assigned = { currency: 'CNY', postingCount: '2', netAmountMinor: '8000', unassigned: false }
    await route.fulfill({ json: { tenantId: 'retail-cn', from: url.searchParams.get('from'), to: url.searchParams.get('to'),
      campaignId: url.searchParams.get('campaignId'), readAt: '2026-09-23T03:00:00Z', tenantWide,
      groups: includePending ? [assigned, { currency: 'CNY', postingCount: '2', netAmountMinor: '0', unassigned: true }] : [assigned],
    } })
  })
  await page.goto('/analytics')
  await page.getByRole('combobox', { name: '活动筛选' }).click()
  await page.getByRole('option', { name: '家电主会场 · cmp-1' }).click()
  await page.getByRole('button', { name: '自定义时间' }).click()
  await chooseDay(page, '报告开始', 17)
  await chooseDay(page, '报告结束', 18)
  await page.getByRole('button', { name: '查询自定义范围' }).click()
  await page.getByRole('button', { name: '读取账本汇总' }).click()
  const table = page.getByRole('table', { name: '补贴成本对账' })
  await expect(table.getByText('¥160.00', { exact: true })).toBeVisible()
  await expect(table.getByText('¥80.00', { exact: true })).toBeVisible()
  await expect(page.getByText('事件成本减账本净补贴')).toBeVisible()
  expect(Object.fromEntries(ledgerReads[0].searchParams)).toEqual({ from: '2026-09-16T16:00:00.000Z', to: '2026-09-18T03:00:00.000Z', campaignId: 'cmp-1' })
  for (const measurement of measurements.slice(-2)) {
    for (const key of ['from', 'to', 'campaignId']) expect(measurement.searchParams.get(key)).toBe(ledgerReads[0].searchParams.get(key))
  }
  await page.screenshot({ path: info.outputPath('historic-subsidy-comparison.png'), fullPage: true })
  tenantWide = false
  await page.getByRole('button', { name: '刷新账本汇总' }).click()
  await expect(page.getByText(/账本仅覆盖当前身份可见的组织和店铺/)).toBeVisible()
  await expect(page.getByText('事件成本减账本净补贴')).toHaveCount(0)
  tenantWide = true
  includePending = true
  await page.getByRole('button', { name: '刷新账本汇总' }).click()
  await expect(table.getByText('待归属', { exact: true })).toBeVisible()
  await expect(page.getByText(/窗口内还有待归属流水/)).toBeVisible()
  await expect(page.getByText('事件成本减账本净补贴')).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(1)
})
