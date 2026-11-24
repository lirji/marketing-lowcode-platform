import { expect, test } from '@playwright/test'

test('operator creates a scoped threshold offer, tests it and submits its saved version', async ({ page }) => {
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { DEMO_MODE: false, API_BASE_URL: '', AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  let campaign: Record<string, unknown> | undefined
  let definition: Record<string, unknown> | undefined
  let simulatedAmount = ''
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    const body = request.method() === 'POST' && request.postData() ? request.postDataJSON() : undefined
    if (path === '/api/v1/campaigns') {
      if (request.method() === 'POST') {
        campaign = { ...body, id: 'threshold-test', status: 'DRAFT', createdAt: '2026-11-24T00:00:00Z' }
        return route.fulfill({ status: 201, json: campaign })
      }
      return route.fulfill({ json: campaign ? [campaign] : [] })
    }
    if (path.endsWith('/definitions/latest')) {
      return definition ? route.fulfill({ json: definition }) : route.fulfill({ status: 404, json: { status: 404, code: 'DEFINITION_NOT_FOUND', title: '未创建 Offer' } })
    }
    if (path.endsWith('/definitions/initial-offer')) {
      expect(request.headers()['idempotency-key']).toBeTruthy()
      expect(body.graph.nodes[1].config).toMatchObject({ shopIds: 'all-shops', skuIds: 'sku-tv', thresholdMinor: '50000', amountMinor: '5000', pricingCurrency: 'CNY', benefitDefinitionVersion: 'coupon@1', fundingRules: 'PLATFORM|retail|10000' })
      definition = { ...body, definitionId: body.graph.definitionId, dialect: body.graph.dialect, version: 1, status: 'DRAFT' }
      return route.fulfill({ status: 201, json: definition })
    }
    if (path.endsWith(':simulate')) {
      expect(path).toContain('/offer-threshold-test/versions/1:simulate')
      expect(body).toMatchObject({ skuId: 'sku-tv', shopId: 'all-shops', currency: 'CNY' })
      simulatedAmount = body.amountMinor
      const hit = simulatedAmount === '50000'
      return route.fulfill({ json: { subtotalMinor: Number(simulatedAmount), discountMinor: hit ? 5000 : 0, payableMinor: Number(simulatedAmount) - (hit ? 5000 : 0), trace: [{ nodeId: 'offer-threshold-test', nodeType: 'product-preview', outcome: '试跑说明' }, { nodeId: 'discount', nodeType: 'product-preview', outcome: hit ? 'APPLIED' : 'THRESHOLD_NOT_MET' }] } })
    }
    if (path.endsWith(':validate')) {
      expect(path).toContain('/offer-threshold-test/versions/1:validate')
      definition = { ...definition, status: 'VALIDATED' }
      return route.fulfill({ json: { valid: true, issues: [] } })
    }
    if (path.endsWith(':submit')) {
      definition = { ...definition, status: 'IN_REVIEW' }
      return route.fulfill({ json: { caseId: 'threshold-approval', definitionId: 'offer-threshold-test', definitionVersion: 1, status: 'OPEN', submittedBy: 'console-admin', updatedAt: '2026-11-24T01:00:00Z' } })
    }
    if (path === '/api/v1/benefits') return route.fulfill({ json: [{ benefitId: 'coupon', version: 1, name: '满减权益', status: 'ACTIVE', benefitSkuId: 'benefit-sku' }] })
    if (path === '/api/v1/funding/accounts') return route.fulfill({ json: [{ resourceKey: 'BUDGET:PLATFORM:retail:CNY', currency: 'CNY', type: 'BUDGET', state: 'ACTIVE', available: 1000000 }] })
    if (path.endsWith('/releases/page')) return route.fulfill({ json: { items: [], nextCursor: null, hasMore: false, state: new URL(request.url()).searchParams.get('state') } })
    if (path.endsWith('/approvals/page')) return route.fulfill({ json: { items: [], nextCursor: null, hasMore: false, status: 'OPEN' } })
    return route.fulfill({ json: [] })
  })

  await page.goto('/campaigns')
  await page.getByRole('button', { name: '从满减模板创建', exact: true }).click()
  await page.getByLabel('活动名称').fill('门店电视满减')
  await page.getByLabel('可衡量目标').fill('验证指定商品的活动转化率')
  await page.getByRole('button', { name: '创建并进入工作台' }).click()
  await expect(page).toHaveURL(/campaigns\/threshold-test\/workbench$/)
  await expect(page.getByRole('heading', { name: '指定门店 SKU 满减' })).toBeVisible()
  await page.getByLabel('商品 SKU ID', { exact: true }).fill('sku-tv')
  await page.getByRole('combobox', { name: '权益定义版本' }).click()
  await page.getByRole('option', { name: '满减权益 · v1' }).click()
  await page.getByRole('combobox', { name: '出资预算账户' }).click()
  await page.getByRole('option', { name: 'BUDGET:PLATFORM:retail:CNY' }).click()
  await page.getByRole('button', { name: '保存满减规则' }).click()
  await expect(page.getByRole('heading', { name: 'Offer 规则 v1' })).toBeVisible()
  await page.getByRole('button', { name: '运行商品试跑' }).click()
  await expect(page.getByText('命中满减')).toBeVisible()
  await page.getByLabel('单商品订单金额（元）').fill('499.99')
  await expect(page.getByText('命中满减')).toHaveCount(0)
  await page.getByRole('button', { name: '运行商品试跑' }).click()
  await expect(page.getByText('未达到门槛')).toBeVisible()
  expect(simulatedAmount).toBe('49999')
  await expect(page.getByRole('button', { name: '提交审核' })).toBeDisabled()
  await page.getByRole('button', { name: '校验当前版本' }).click()
  await page.getByRole('button', { name: '提交审核' }).click()
  await expect(page.getByText('审核单 threshold-approval')).toBeVisible()
  await expect(page.getByRole('link', { name: '打开发布中心' })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Offer 规则 v1' })).toBeVisible()
  await expect(page.getByText('审核中', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
})
