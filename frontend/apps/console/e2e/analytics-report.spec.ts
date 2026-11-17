import { readFile } from 'node:fs/promises'
import { expect, test } from '@playwright/test'

const fixture = { counts: { CONVERSION_CONFIRMED: 3, '=unsafe()': 1 }, attributedRevenueMinor: 12345, fundingCostMinor: 67, roi: '183.2537', completeThrough: '2026-11-17T00:00:00Z', lag: { seconds: 12 } }

test('analytics exports the selected report and recovers from query failure', async ({ page }, info) => {
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/client-errors', route => route.fulfill({ status: 204 }))
  let failing = false
  const requests: URL[] = []
  await page.route('**/api/v1/measurements/**', async route => {
    const url = new URL(route.request().url()); requests.push(url)
    if (failing) { await route.fulfill({ status: 503, json: { status: 503, title: 'Unavailable', detail: '隔离故障验证', type: 'about:blank' } }); return }
    await route.fulfill({ json: url.pathname.endsWith('/dashboard') ? fixture : { points: [{ at: url.searchParams.get('from'), revenueMinor: 12345, costMinor: 67, conversions: 3 }] } })
  })
  await page.goto('/analytics')
  await expect(page.getByRole('button', { name: '导出当前报告' })).toBeEnabled()
  await expect(page.getByText('123.45', { exact: true })).toBeVisible()
  expect(requests[0].searchParams.get('from')).toBe(requests[1].searchParams.get('from'))
  expect(requests[0].searchParams.get('to')).toBe(requests[1].searchParams.get('to'))
  await page.getByRole('button', { name: '30 天', exact: true }).click()
  await expect(page.getByRole('button', { name: '导出当前报告' })).toBeEnabled()
  const downloadEvent = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出当前报告' }).click()
  const download = await downloadEvent
  const csv = await readFile((await download.path())!, 'utf8')
  expect(csv).toContain('"归因收入","12345","分"')
  expect(csv).toContain('"\'=unsafe()"')
  expect(csv).toContain('"时间粒度","day"')
  expect(download.suggestedFilename()).toMatch(/^marketing-report-.*\.csv$/)
  await page.screenshot({ path: info.outputPath('analytics-report.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
  failing = true
  await page.getByRole('button', { name: '7 天', exact: true }).click()
  await expect(page.getByText('报告加载失败', { exact: true })).toBeVisible({ timeout: 20000 })
  await expect(page.getByRole('button', { name: '导出当前报告' })).toBeDisabled()
  await expect(page.getByText('123.45', { exact: true })).toHaveCount(0)
  await page.screenshot({ path: info.outputPath('analytics-failure.png') })
  failing = false
  await page.getByRole('button', { name: '刷新数据' }).click()
  await expect(page.getByRole('button', { name: '导出当前报告' })).toBeEnabled()
  await expect(page.getByRole('button', { name: '7 天', exact: true })).toHaveAttribute('aria-pressed', 'true')
})

test('analytics reads and downloads the actual local measurement report', async ({ page }, info) => {
  test.skip(process.env.MARKETING_LIVE_ANALYTICS !== '1', '需要已授权的本地网关与测量服务；仅GET查询')
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/client-errors', route => route.fulfill({ status: 204 }))
  const receipts: { path: string; from: string | null; to: string | null; status: number; body: Record<string, unknown> }[] = [], errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/api/v1/measurements/**', async route => {
    expect(route.request().method()).toBe('GET')
    const url = new URL(route.request().url())
    const response = await route.fetch({ url: 'http://127.0.0.1:18080' + url.pathname + url.search, maxRetries: 0 })
    const body = await response.json()
    receipts.push({ path: url.pathname, from: url.searchParams.get('from'), to: url.searchParams.get('to'), status: response.status(), body })
    await route.fulfill({ response })
  })
  await page.goto('/analytics')
  await expect(page.getByRole('button', { name: '导出当前报告' })).toBeEnabled({ timeout: 20000 })
  await page.getByRole('button', { name: '7 天', exact: true }).click()
  await expect(page.getByRole('button', { name: '导出当前报告' })).toBeEnabled({ timeout: 20000 })
  const dashboard = receipts.filter(row => row.path.endsWith('/dashboard')).at(-1)!
  const series = receipts.filter(row => row.path.endsWith('/series')).at(-1)!
  expect(dashboard.status).toBe(200); expect(series.status).toBe(200)
  expect(dashboard.from).toBe(series.from); expect(dashboard.to).toBe(series.to)
  const downloadEvent = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出当前报告' }).click()
  const download = await downloadEvent
  const csv = await readFile((await download.path())!, 'utf8')
  expect(csv).toContain(`"归因收入","${dashboard.body.attributedRevenueMinor}","分"`)
  expect(csv).toContain(`"补贴成本","${dashboard.body.fundingCostMinor}","分"`)
  const pointCount = (series.body.points as unknown[]).length
  expect(csv.match(/"时间序列"/g)?.length ?? 0).toBe(pointCount * 3)
  expect(errors).toEqual([])
  await download.saveAs(info.outputPath('actual-report.csv'))
  await info.attach('actual-requests', { body: JSON.stringify(receipts, null, 2), contentType: 'application/json' })
  await page.screenshot({ path: info.outputPath('analytics-live.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
})

test('campaign selection requires a server-confirmed scope and preserves it in the downloaded CSV', async ({ page }, info) => {
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/client-errors', route => route.fulfill({ status: 204 }))
  await page.route('**/api/v1/campaigns', route => route.fulfill({ json: [{ id: 'cmp-a', name: '活动甲', objective: '隔离筛选', status: 'DRAFT', createdAt: '2026-11-17T00:00:00Z' }, { id: 'cmp-b', name: '活动乙', objective: '旧服务拒绝', status: 'DRAFT', createdAt: '2026-11-17T00:00:00Z' }] }))
  await page.route('**/api/v1/measurements/**', route => {
    const url = new URL(route.request().url()), selected = url.searchParams.get('campaignId') || ''
    const echo = selected === 'cmp-b' ? {} : { campaignId: selected }
    return route.fulfill({ json: url.pathname.endsWith('/dashboard') ? { ...fixture, ...echo, attributedRevenueMinor: selected ? 100 : 12345 } : { points: [], ...echo } })
  })
  await page.goto('/analytics')
  await expect(page.getByRole('button', { name: '导出当前报告' })).toBeEnabled()
  await page.getByRole('combobox', { name: '活动筛选' }).click()
  await page.getByRole('option', { name: '活动甲 · cmp-a' }).click()
  await expect(page.getByText('1.00', { exact: true })).toBeVisible()
  const downloadEvent = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出当前报告' }).click()
  expect(await readFile((await (await downloadEvent).path())!, 'utf8')).toContain('"活动","cmp-a"')
  await page.screenshot({ path: info.outputPath('analytics-campaign.png'), fullPage: true })
  await page.getByRole('combobox', { name: '活动筛选' }).click()
  await page.getByRole('option', { name: '活动乙 · cmp-b' }).click()
  await expect(page.getByText('后端未确认活动筛选范围，请升级测量服务后重试')).toBeVisible({ timeout: 20000 })
  await expect(page.getByRole('button', { name: '导出当前报告' })).toBeDisabled()
  await page.getByRole('combobox', { name: '活动筛选' }).click()
  await page.getByRole('option', { name: '全部活动' }).click()
  await expect(page.getByText('123.45', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
})

test('actual campaign filtering excludes another campaign from MySQL totals and export', async ({ page }, info) => {
  test.skip(process.env.MARKETING_LIVE_CAMPAIGN_FILTER !== '1', '需要隔离18884测量服务及已落库的活动事实；仅GET查询')
  const setup = JSON.parse(await readFile(process.env.MARKETING_CAMPAIGN_FIXTURE_PATH!, 'utf8')) as { campaigns: { id: string; name: string }[] }
  const first = setup.campaigns[0], second = setup.campaigns[1]
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/client-errors', route => route.fulfill({ status: 204 }))
  await page.route('**/api/v1/campaigns', async route => route.fulfill({ response: await route.fetch({ url: 'http://127.0.0.1:18080/api/v1/campaigns' }) }))
  const receipts: { path: string; campaignId: string; body: Record<string, unknown> }[] = [], errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/api/v1/measurements/**', async route => {
    expect(route.request().method()).toBe('GET')
    const url = new URL(route.request().url())
    const response = await route.fetch({ url: 'http://127.0.0.1:18884' + url.pathname + url.search, maxRetries: 0 })
    expect(response.status()).toBe(200)
    receipts.push({ path: url.pathname, campaignId: url.searchParams.get('campaignId') ?? '', body: await response.json() })
    await route.fulfill({ response })
  })
  await page.goto('/analytics')
  await expect(page.getByText('300.00', { exact: true })).toBeVisible()
  await page.getByRole('combobox', { name: '活动筛选' }).click()
  await page.getByRole('option', { name: first.name + ' · ' + first.id, exact: true }).click()
  await expect(page.getByText('100.00', { exact: true })).toBeVisible()
  const response = receipts.filter(row => row.campaignId === first.id && row.path.endsWith('/dashboard')).at(-1)!
  expect(response.body.campaignId).toBe(first.id)
  expect(response.body.attributedRevenueMinor).toBe(10000)
  const downloadEvent = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出当前报告' }).click()
  const download = await downloadEvent
  const csv = await readFile((await download.path())!, 'utf8')
  expect(csv).toContain(`"活动","${first.id}"`)
  expect(csv).toContain('"归因收入","10000","分"')
  expect(csv).not.toContain(second.id)
  await download.saveAs(info.outputPath('filtered-actual-report.csv'))
  await page.screenshot({ path: info.outputPath('analytics-campaign-live.png'), fullPage: true })
  await page.getByRole('combobox', { name: '活动筛选' }).click()
  await page.getByRole('option', { name: second.name + ' · ' + second.id, exact: true }).click()
  await expect(page.getByText('200.00', { exact: true })).toBeVisible()
  await info.attach('actual-filtered-requests', { body: JSON.stringify(receipts, null, 2), contentType: 'application/json' })
  expect(errors).toEqual([])
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
})
