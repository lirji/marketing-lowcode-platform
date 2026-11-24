import { expect, test } from '@playwright/test'
import type { OfferComparisonInput } from '../src/shared/api/schemas'

test('compares two saved Offer versions on the same scenarios and recovers from a failed run', async ({ page }) => {
  await page.addInitScript(() => {
    window.__MARKETING_CONFIG__ = { DEMO_MODE: false, API_BASE_URL: '', AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true }
  })
  const campaignId = 'comparison-campaign'
  const definitionId = 'comparison-offer'
  const createdAt = '2026-11-24T00:00:00Z'
  const version = (number: number, status: string) => ({ version: number, status, createdAt })
  const scope = { campaignId, definitionId }
  let comparisons = 0
  await page.route('**/api/v1/**', async route => {
    const request = route.request()
    const { pathname, searchParams } = new URL(request.url())
    if (pathname === '/api/v1/campaigns') {
      return route.fulfill({ json: [{
        id: campaignId, name: '满减版本对比活动', objective: '测试门槛差异', status: 'DRAFT', campaignType: 'STANDARD',
        organizationId: 'retail-business', shopId: 'all-shops', createdAt,
      }] })
    }
    if (pathname.endsWith('/latest')) {
      return route.fulfill({ json: {
        ...scope, ...version(3, 'DRAFT'), dialect: 'OFFER_DECISION_DAG',
        graph: {
          definitionId, dialect: 'OFFER_DECISION_DAG', dialectVersion: '1.0.0', variables: {},
          annotations: { template: 'STORE_SKU_FIXED_DISCOUNT_V1' }, edges: [],
          nodes: [{ id: 'discount', stableTypeId: 'offer.fixed', semanticVersion: '1.0.0', config: {
            shopIds: 'all-shops', skuIds: 'sku-tv', amountMinor: '6000', thresholdMinor: '50000', pricingCurrency: 'CNY',
            validFrom: createdAt, validTo: '2026-12-01T00:00:00Z', benefitDefinitionVersion: 'coupon@1',
          } }],
        },
      } })
    }
    if (pathname.endsWith('/versions')) {
      expect(searchParams.get('campaignId')).toBe(campaignId)
      const before = searchParams.get('beforeVersion')
      return route.fulfill({ json: before === '2'
        ? { ...scope, items: [version(1, 'RELEASED')], nextBeforeVersion: null }
        : { ...scope, items: [version(3, 'DRAFT'), version(2, 'IN_REVIEW')], nextBeforeVersion: 2 } })
    }
    if (pathname.endsWith('/versions:compare-offer')) {
      const input = request.postDataJSON() as OfferComparisonInput
      expect(input).toMatchObject({ campaignId, baselineVersion: 1, candidateVersion: 3 })
      expect(input.scenarios.map(item => item.amountMinor)).toEqual(['49999', '50000', '50001'])
      expect(input.scenarios[2]).toMatchObject({ skuId: 'other-sku', shopId: 'all-shops', occurredAt: createdAt })
      comparisons++
      if (comparisons === 1) return route.fulfill({ status: 503, json: { status: 503, title: '服务暂不可用' } })
      return route.fulfill({ json: {
        baselineVersion: 1, candidateVersion: 3,
        rows: input.scenarios.map(item => {
          const amount = Number(item.amountMinor)
          const side = (discount: number) => ({
            discountMinor: discount, payableMinor: amount - discount,
            outcome: item.skuId !== 'sku-tv' ? 'PRODUCT_SCOPE_MISMATCH' : discount ? 'APPLIED' : 'THRESHOLD_NOT_MET',
          })
          const hit = item.skuId === 'sku-tv' && amount >= 50000
          return { baseline: side(hit ? 5000 : 0), candidate: side(hit ? 6000 : 0) }
        }),
      } })
    }
    return route.fulfill({ json: [] })
  })

  await page.goto(`/campaigns/${campaignId}/workbench`)
  await page.getByRole('button', { name: '展开对比' }).click()
  await expect(page.getByRole('combobox', { name: '候选版本' })).toContainText('v3')
  await page.getByRole('button', { name: '加载更早版本' }).click()
  await page.getByRole('combobox', { name: '基准版本' }).click()
  await page.getByRole('option', { name: /v1 · 已发布/ }).click()
  const last = page.getByRole('group', { name: '场景 3', exact: true })
  await last.getByRole('textbox', { name: '商品 SKU ID' }).fill('other-sku')
  const run = page.getByRole('button', { name: '运行版本对比' })
  await run.click()
  await expect(page.getByText('版本对比失败')).toBeVisible()
  await expect(last.getByRole('textbox', { name: '商品 SKU ID' })).toHaveValue('other-sku')
  await run.click()
  const results = page.getByRole('table', { name: '满减版本对比结果' })
  await expect(results.getByRole('row')).toHaveCount(4)
  await expect(results.getByRole('row', { name: /场景 2/ })).toContainText('¥440.00')
  await expect(results.getByRole('row', { name: /场景 3/ })).toContainText('商品或门店不在范围内')
  await expect(page.getByText('3 个场景完成对比，1 个场景的应付金额有变化。', { exact: false })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
  await page.getByRole('button', { name: '收起对比' }).click()
  await expect(results).toBeHidden()
  await page.getByRole('button', { name: '展开对比' }).click()
  await expect(results).toBeVisible()
  await last.getByRole('textbox', { name: '订单金额（元）' }).fill('700.00')
  await expect(results).toHaveCount(0)
})
