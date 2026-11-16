import { expect, test } from '@playwright/test'

test('award delivery and observed fulfillment stay separate through refresh failure', async ({ page }, info) => {
  const writes: string[] = []
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/v1/campaigns', (route) => route.fulfill({ json: [{ id: 'award-campaign', name: '发奖合同验收', objective: '转化', status: 'ACTIVE', createdAt: '2026-11-16T00:00:00Z' }] }))
  await page.route('**/api/v1/award-intents?*', (route) => route.fulfill({ json: Array.from({ length: 20 }, (_, index) => ({
    intentId: `intent-${index + 1}`, sourceSystem: 'drools-activity', sourceRequestId: 'source-1', campaignId: 'award-campaign',
    definitionVersion: 1, subjectHash: 'a'.repeat(64), deliveryMode: 'CENTER', status: 'SENT', deliveryResult: 'CENTER_ACCEPTED',
    attempts: 1, benefitOrderNo: 'order-1', lastError: '', createdAt: '2026-11-16T00:00:00Z', updatedAt: '2026-11-16T00:01:00Z', sentAt: '2026-11-16T00:01:00Z',
  })) }))
  const observed = { intentId: 'intent-1', synchronizationState: 'PAUSED', benefitOrderNo: 'order-1', centerStatus: 'SUCCEEDED', checkedAt: '2026-11-16T01:00:00Z',
    errorCode: 'AWARD_CENTER_QUERY_TIMEOUT', consecutiveFailures: 10, refreshEnabled: true,
    items: [{ clientItemId: 'item-1', skuId: 'sku-1', skuVersion: 3, quantity: 1, status: 'SUCCEEDED' }] }
  await page.route('**/api/v1/award-intents/intent-1/fulfillment', (route) => route.fulfill({ json: observed }))
  await page.route('**/api/v1/award-intents/intent-1/fulfillment:refresh', (route) => {
    writes.push(route.request().url())
    return route.fulfill({ status: 503, json: { type: 'about:blank', title: '暂不可用', status: 503, code: 'QUERY_UNAVAILABLE', detail: '查询暂不可用，请稍后重试', retryable: true } })
  })
  await page.goto('/operations?tab=awards&campaignId=award-campaign')
  await expect(page.getByText('已送达', { exact: true }).first()).toBeVisible()
  const trigger = page.getByRole('button', { name: '查看发放结果' }).first()
  await trigger.click()
  await expect(page.getByRole('region', { name: '发放结果详情' })).toBeFocused()
  await expect(page.getByText('发放成功', { exact: true })).toBeVisible()
  await expect(page.getByText('自动查询已暂停', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '重新查询权益中心' }).click()
  await expect(page.getByText('请求刷新失败', { exact: true })).toBeVisible()
  await expect(page.getByText('发放成功', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '关闭结果' }).click()
  await expect(trigger).toBeFocused()
  await trigger.click()
  await expect(page.getByText('发放成功', { exact: true })).toBeVisible()
  expect(writes).toHaveLength(1)
  expect(errors).toEqual([])
  expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(1)
  await page.getByRole('region', { name: '发放结果详情' }).screenshot({ path: info.outputPath('fulfillment-observation.png') })
})
