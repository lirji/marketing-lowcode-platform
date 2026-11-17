import { expect, test } from '@playwright/test'

test('static campaign preview never invents records or creates a local campaign', async ({ page }, info) => {
  const requests: string[] = []
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: true, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', route => { requests.push(route.request().url()); return route.fulfill({ status: 500, json: {} }) })
  await page.goto('/campaigns?create=true')
  await expect(page.getByText('活动列表需要连接后端')).toBeVisible()
  await expect(page.getByRole('button', { name: '创建活动', exact: true })).toBeDisabled()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByRole('table', { name: '营销活动' })).toHaveCount(0)
  await expect(page.getByText('双11家电主会场', { exact: true })).toHaveCount(0)
  await page.screenshot({ path: info.outputPath('campaign-static-preview.png') })
  expect(requests).toEqual([])
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
})
