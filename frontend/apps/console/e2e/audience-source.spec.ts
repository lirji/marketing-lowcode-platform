import { expect, test } from '@playwright/test'

test('audience authoring preserves rules without fabricating population or snapshots', async ({ page }, info) => {
  const writes: string[] = []
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', (route) => {
    if (route.request().method() !== 'GET') writes.push(route.request().url())
    return route.fulfill({ json: [] })
  })
  await page.route('**/api/v1/fields', (route) => route.fulfill({ json: [{ fieldId: 'level', valueType: 'STRING', owner: 'member', provenance: 'contract-fixture', classification: 'INTERNAL', maxAgeSeconds: 300 }] }))
  await page.route('**/api/v1/audiences', (route) => route.fulfill({ json: [{ segmentId: 'saved-rule', version: 1, name: '会员等级规则', status: 'ACTIVE', rule: { match: 'ALL', conditions: [{ fieldId: 'level', operator: 'EQ', value: 'GOLD' }] } }] }))
  await page.route('**/api/v1/audiences/profile-source', (route) => route.fulfill({ json: { enabled: false, fieldIds: [] } }))
  await page.goto('/designers/audience')
  await expect(page.getByRole('heading', { name: '会员等级规则' })).toBeVisible()
  await expect(page.getByText('画像来源尚未接入')).toBeVisible()
  await expect(page.getByRole('button', { name: '预估人数' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '保存快照' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '保存规则' })).toBeEnabled()
  await page.getByLabel('条件 1 值').fill('SILVER')
  await expect(page.getByRole('button', { name: '预估人数' })).toBeDisabled()
  expect(writes).toEqual([])
  expect(errors).toEqual([])
  expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(1)
  await page.screenshot({ path: info.outputPath('audience-source-pending.png'), fullPage: true })
})
