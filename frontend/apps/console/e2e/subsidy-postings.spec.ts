import { expect, test } from '@playwright/test'
import { readFile } from 'node:fs/promises'

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
  const orderRequests: URL[] = []
  await page.route('**/api/v1/funding/subsidy-postings/by-order?*', route => {
    expect(route.request().method()).toBe('GET')
    const url = new URL(route.request().url())
    orderRequests.push(url)
    const orderId = url.searchParams.get('orderId')
    return route.fulfill({ json: {
      orderId: orderRequests.length === 1 ? 'wrong-order' : orderId,
      page: {
        from: url.searchParams.get('from'), to: url.searchParams.get('to'), campaignId: url.searchParams.get('campaignId'),
        unassigned: false, hasMore: false, nextCursor: null,
        items: [{ ledgerId: 'order-refund', postedAt: '2026-09-16T16:00:00Z', applicationId: 'application-1',
          campaignId: null, currency: 'CNY', resourceKey: 'BUDGET:PLATFORM:platform:CNY', operation: 'REFUND', amountMinor: '-5000' }],
      },
    } })
  })
  await page.route('**/api/v1/funding/subsidy-postings/by-order/bindings?*', route => route.fulfill({
    status: 404, json: { status: 404, title: 'Not Found' },
  }))
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
        applicationId: more ? '=ext-app' : 'application-1', campaignId, currency: 'CNY', resourceKey: 'BUDGET:PLATFORM:platform:CNY',
        operation: more ? 'CONFIRM' : 'REFUND', amountMinor: more ? '9007199254740993' : '-9007199254740992' }],
    } })
  })
  await page.goto('/operations')
  await page.getByRole('tab', { name: '资金对账' }).click()
  await page.getByRole('combobox', { name: '活动归属' }).click()
  await page.getByRole('option', { name: '仅待归属流水' }).click()
  await page.getByRole('button', { name: '查询补贴流水' }).click()
  const table = page.getByRole('table', { name: '补贴流水' })
  const download = async () => {
    const [csv] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: '导出已加载流水' }).click()])
    expect(csv.suggestedFilename()).toMatch(/^subsidy-postings-.*\.csv$/)
    return readFile((await csv.path())!, 'utf8')
  }
  await expect(table.getByText('9,007,199,254,740,993', { exact: true })).toBeVisible()
  await expect(table.getByText('待归属', { exact: true })).toBeVisible()
  const partial = await download()
  expect(partial).toContain('"9007199254740993","CNY","补贴确认"')
  expect(partial).toContain('"\'=ext-app"')
  expect(partial).toContain('"retail-cn","2026-09-16T16:00:00.000Z"')
  expect(partial).toContain('"还有下一页"')
  expect(partial).not.toContain('posting-refund')
  await page.getByRole('button', { name: '加载更多流水' }).click()
  await expect(table.getByText('退款或冲正', { exact: true })).toBeVisible()
  await expect(page.getByText(/已加载净额 CNY 1（最小货币单位）/)).toBeVisible()
  const allLoaded = await download()
  expect(allLoaded).toContain('"-9007199254740992","CNY","退款或冲正"')
  expect(allLoaded).toContain('"已读取至最后一页"')
  expect(allLoaded).not.toContain('"还有下一页"')
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
  const readsBeforeOrder = requests.length
  await page.getByRole('link', { name: '查看该订单补贴流水' }).click()
  await expect(page).toHaveURL(/\/operations\?tab=funding$/)
  await expect(page.getByRole('textbox', { name: '订单号' })).toHaveValue('settlement-order')
  await expect(page.getByText('资金服务未确认订单号，请重新查询。')).toBeVisible()
  await expect(table).toHaveCount(0)
  await expect(page.getByRole('button', { name: '导出已加载流水' })).toHaveCount(0)
  await page.getByRole('button', { name: '查询补贴流水' }).click()
  await expect(table.getByText('退款或冲正')).toBeVisible()
  await expect(table.getByText('-5,000', { exact: true })).toBeVisible()
  await expect(page.getByText('当前资金服务尚未提供订单绑定查询。')).toBeVisible()
  expect(await download()).toContain('"settlement-order","已读取至最后一页"')
  expect(requests).toHaveLength(readsBeforeOrder)
  expect(orderRequests.map(url => url.searchParams.get('orderId'))).toEqual(['settlement-order', 'settlement-order'])
  expect(orderRequests.at(-1)?.searchParams.get('from')).toBe('2026-09-10T03:00:00.000Z')

  const longOrder = 'W'.repeat(128)
  await page.getByRole('textbox', { name: '订单号', exact: true }).fill(longOrder)
  await page.getByRole('button', { name: '查询补贴流水' }).click()
  const orderScope = page.getByText('订单号 ' + longOrder, { exact: true })
  await expect(orderScope).toBeVisible()
  expect(await orderScope.evaluate(label => label.scrollWidth)).toBeLessThanOrEqual(await orderScope.evaluate(label => label.clientWidth) + 1)
})

test('order reconciliation explains binding evidence even when no subsidy has posted', async ({ page }, info) => {
  await page.clock.setFixedTime(new Date('2026-09-17T03:00:00Z'))
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/funding/reconciliation', route => route.fulfill({ json: { balanced: true, violations: [] } }))
  await page.route('**/api/v1/funding/subsidy-postings/by-order?*', route => {
    const params = new URL(route.request().url()).searchParams
    return route.fulfill({ json: { orderId: params.get('orderId'),
      page: { from: params.get('from'), to: params.get('to'), campaignId: null, unassigned: true,
        items: [], nextCursor: null, hasMore: false } } })
  })
  await page.route('**/api/v1/funding/subsidy-postings/by-order/bindings?*', route => {
    expect(route.request().method()).toBe('GET')
    const params = new URL(route.request().url()).searchParams
    expect(params.has('from')).toBe(false)
    expect(params.has('to')).toBe(false)
    expect(params.get('unassigned')).toBe('true')
    expect(params.get('includeCheckoutBinding')).toBe('true')
    const after = params.get('after')
    expect(after).toBe(after ? 'application-waiting' : null)
    const bound = { campaignId: null, boundAt: '2026-09-15T23:34:00Z', evidenceVersion: '9007199254740993' }
    return route.fulfill({ json: { orderId: params.get('orderId'), campaignId: null, unassigned: true,
      items: after ? [{ ...bound, applicationId: 'application-with-receipt', status: 'BOUND' },
        { ...bound, applicationId: 'application-without-receipt', status: 'EVIDENCE_MISSING' },
        { ...bound, applicationId: 'application-checkout', orderNo: 'TRD-ORD-260916-0123456789', status: 'CHECKOUT_BOUND', evidenceVersion: null }]
        : [{ applicationId: 'application-waiting', campaignId: null, status: 'UNBOUND', evidenceVersion: null, boundAt: null }],
      nextCursor: after ? null : 'application-waiting' } })
  })

  await page.goto('/operations')
  await page.getByRole('tab', { name: '资金对账' }).click()
  await page.getByRole('combobox', { name: '活动归属' }).click()
  await page.getByRole('option', { name: '仅待归属流水' }).click()
  await page.getByRole('textbox', { name: '订单号' }).fill('ORD-42')
  await page.getByRole('button', { name: '查询补贴流水' }).click()
  await expect(page.getByText('没有匹配的补贴流水')).toBeVisible()
  const table = page.getByRole('table', { name: '订单绑定证据' })
  await expect(table.getByRole('cell', { name: '待绑定', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '加载更多绑定证据' }).click()
  const bound = table.getByRole('row').filter({ has: page.getByRole('cell', { name: 'application-with-receipt', exact: true }) })
  await expect(bound.getByRole('cell', { name: '已绑定', exact: true })).toBeVisible()
  await expect(bound.getByRole('cell', { name: '9007199254740993', exact: true })).toBeVisible()
  await expect(bound.locator('time')).toHaveAttribute('datetime', '2026-09-15T23:34:00Z')
  await expect(table.getByRole('cell', { name: '首次回执待核查' })).toBeVisible()
  const checkout = table.getByRole('row').filter({ has: page.getByRole('cell', { name: 'application-checkout', exact: true }) })
  await expect(checkout.getByRole('cell', { name: '试点订单已绑定', exact: true })).toBeVisible()
  await expect(checkout.getByRole('cell', { name: 'TRD-ORD-260916-0123456789', exact: true })).toBeVisible()
  await expect(checkout.getByRole('cell', { name: '—', exact: true })).toBeVisible()
  await expect(checkout.locator('time')).toHaveAttribute('datetime', '2026-09-15T23:34:00Z')
  await expect(page.getByRole('button', { name: '加载更多绑定证据' })).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(1)
  await page.screenshot({ path: info.outputPath('order-binding-evidence.png'), fullPage: true })
})
