import { expect, test, type Page } from '@playwright/test'
import { readFile } from 'node:fs/promises'

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
  let includeGroups = true
  let includeBreakdown = true
  let includeForeign = false
  let unmatchedPostingCount: string | undefined = '0'
  await page.route('**/api/v1/funding/subsidy-postings/summary?*', async route => {
    expect(route.request().method()).toBe('GET')
    const url = new URL(route.request().url())
    ledgerReads.push(url)
    const assigned = { currency: 'CNY', postingCount: '2', netAmountMinor: '8000', unassigned: false,
      breakdown: includeBreakdown ? { confirmedAmountMinor: '15000', reversedAmountMinor: '7000' } : undefined }
    const groups = includeGroups ? [assigned] : []
    if (includePending) groups.push({ ...assigned, netAmountMinor: '0', unassigned: true,
      breakdown: { confirmedAmountMinor: '5000', reversedAmountMinor: '5000' } })
    if (includeForeign) groups.push({ ...assigned, currency: 'JPY', netAmountMinor: '9007199254740993',
      breakdown: { confirmedAmountMinor: '9007199254740994', reversedAmountMinor: '1' } })
    await route.fulfill({ json: { tenantId: 'retail-cn', from: url.searchParams.get('from'), to: url.searchParams.get('to'),
      campaignId: url.searchParams.get('campaignId'), readAt: '2026-09-23T03:00:00Z', tenantWide,
      unmatchedPostingCount: tenantWide ? unmatchedPostingCount : null,
      groups,
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
  await expect(table.getByText('¥150.00', { exact: true })).toBeVisible()
  await expect(table.getByText('¥70.00', { exact: true })).toBeVisible()
  const tableRegion = page.getByRole('region', { name: '补贴成本对账表格' })
  await table.getByText('¥70.00', { exact: true }).scrollIntoViewIfNeeded()
  await expect(table.getByText('¥70.00', { exact: true })).toBeInViewport()
  await expect(page.getByText('事件成本减账本净补贴')).toBeVisible()
  expect(Object.fromEntries(ledgerReads[0].searchParams)).toEqual({ from: '2026-09-16T16:00:00.000Z', to: '2026-09-18T03:00:00.000Z', campaignId: 'cmp-1', includeBreakdown: 'true' })
  const [summaryFile] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: '导出账本汇总' }).click()])
  expect(summaryFile.suggestedFilename()).toMatch(/^subsidy-ledger-summary-.*\.csv$/)
  const csv = await readFile((await summaryFile.path())!, 'utf8')
  expect(csv).toContain('"retail-cn","租户","cmp-1","2026-09-16T16:00:00.000Z","2026-09-18T03:00:00.000Z"')
  expect(csv).toContain('"已归属","CNY","15000","7000","8000","2","0"')
  for (const measurement of measurements.slice(-2)) {
    for (const key of ['from', 'to', 'campaignId']) expect(measurement.searchParams.get(key)).toBe(ledgerReads[0].searchParams.get(key))
  }
  await tableRegion.screenshot({ path: info.outputPath('historic-subsidy-comparison.png'), scale: 'css' })
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
  includePending = false
  includeForeign = true
  await page.getByRole('button', { name: '刷新账本汇总' }).click()
  await expect(table.getByText('9,007,199,254,740,994（最小货币单位）', { exact: true })).toBeVisible()
  await expect(page.getByText(/该窗口含其他币种/)).toBeVisible()
  includeForeign = false
  includeBreakdown = false
  await page.getByRole('button', { name: '刷新账本汇总' }).click()
  await expect(page.getByText('当前资金服务未返回账本分项，表中仍可核对净额。')).toBeVisible()
  await expect(table.getByText('¥80.00', { exact: true })).toBeVisible()
  await expect(table.getByText('¥150.00', { exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '导出账本汇总' })).toHaveCount(0)
  includeBreakdown = true
  unmatchedPostingCount = '2'
  await page.getByRole('button', { name: '刷新账本汇总' }).click()
  await expect(page.getByText(/有 2 笔流水缺少有效的资金关联/)).toBeVisible()
  await expect(table.getByText('¥80.00', { exact: true })).toBeVisible()
  await expect(page.getByText('事件成本减账本净补贴')).toHaveCount(0)
  includeGroups = false
  unmatchedPostingCount = undefined
  await page.getByRole('button', { name: '刷新账本汇总' }).click()
  await expect(page.getByText(/账本接口尚未返回流水关联校验结果/)).toBeVisible()
  await expect(page.getByText('所选范围没有可见的补贴流水')).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(1)
})
