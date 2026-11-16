import { expect, test } from '@playwright/test'

test('DMN draft save, reload and conflict recovery preserve user choices', async ({ page }, info) => {
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/client-error', (route) => route.fulfill({ status: 204 }))
  await page.route('**/api/v1/campaigns', (route) => route.fulfill({ json: [{ id: '85441433-eca9-4f49-ade5-b8fe795adf86', name: '草稿恢复测试活动', objective: 'test', status: 'DRAFT', campaignType: 'STANDARD', createdAt: '2026-11-16T00:00:00Z' }] }))
  let saved: unknown
  let version = 0
  await page.route('**/api/v1/campaigns/85441433-eca9-4f49-ade5-b8fe795adf86/dmn-drafts/**', (route) => saved
    ? route.fulfill({ json: saved }) : route.fulfill({ status: 404, json: { type: 'about:blank', title: 'Not found', status: 404, code: 'DMN_DRAFT_NOT_FOUND', detail: '没有草稿' } }))
  await page.route('**/api/v1/campaigns/85441433-eca9-4f49-ade5-b8fe795adf86/dmn-drafts', async (route) => {
    const body = route.request().postDataJSON()
    expect(route.request().headers()['idempotency-key']).toBeTruthy()
    if (body.expectedVersion !== version) {
      await route.fulfill({ status: 409, json: { type: 'about:blank', title: 'Conflict', status: 409, code: 'DMN_DRAFT_VERSION_CONFLICT', detail: '版本冲突' } }); return
    }
    version++
    saved = { campaignId: '85441433-eca9-4f49-ade5-b8fe795adf86', version, contentHash: 'a'.repeat(64), createdBy: 'test-author', createdAt: '2026-11-16T00:00:00Z', table: body.table }
    await route.fulfill({ json: saved })
  })
  await page.goto('/designers/dmn')
  await page.getByLabel('输入字段 1 名称').fill('amount')
  await page.getByLabel('输出字段 1 名称').fill('result')
  await page.getByLabel('规则 1 输入 1').fill('>= 100')
  await page.getByLabel('规则 1 输出 1').fill('"VIP"')
  await page.getByRole('combobox', { name: 'DMN 归属活动' }).click()
  await page.getByRole('option', { name: /草稿恢复测试活动/ }).click()
  await expect(page.getByLabel('规则 1 输入 1')).toHaveValue('>= 100')
  await page.getByRole('button', { name: '保存草稿版本' }).click()
  await expect(page.getByText('已保存 · v1', { exact: true })).toBeVisible()
  await page.reload()
  await page.getByRole('combobox', { name: 'DMN 归属活动' }).click()
  await page.getByRole('option', { name: /草稿恢复测试活动/ }).click()
  await expect(page.getByLabel('规则 1 输出 1')).toHaveValue('"VIP"')
  await page.screenshot({ path: info.outputPath('dmn-restored.png'), fullPage: true })
  version = 2
  saved = { ...saved as object, version }
  await page.getByLabel('规则 1 输出 1').fill('"mine"')
  await page.getByRole('button', { name: '保存草稿版本' }).click()
  await expect(page.getByText('草稿版本冲突', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '加载最新版本' }).click()
  await expect(page.getByRole('group', { name: '确认替换未保存草稿' })).toBeVisible()
  await page.getByRole('button', { name: '保留当前编辑' }).click()
  await expect(page.getByLabel('规则 1 输出 1')).toHaveValue('"mine"')
  await page.screenshot({ path: info.outputPath('dmn-conflict.png'), fullPage: true })
  await page.getByRole('button', { name: '加载最新版本' }).click()
  await page.getByRole('button', { name: '放弃修改并加载' }).click()
  await expect(page.getByText('已保存 · v2', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
})
