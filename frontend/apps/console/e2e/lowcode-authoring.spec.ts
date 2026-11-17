import { expect, test, type Page } from '@playwright/test'
import type { GraphDefinition } from '../src/shared/api/schemas'

const live = process.env.MARKETING_LIVE_UI === '1'
const run = `ui-${Date.now()}`
const registry = ['offer.start', 'offer.fixed', 'offer.percentage', 'offer.end', 'offer.condition'].map((stableTypeId) => ({
  stableTypeId, semanticVersion: '1.0.0', dialects: ['OFFER_DECISION_DAG'], costWeight: 10,
  inputPorts: stableTypeId === 'offer.start' ? {} : { in: 'flow' }, outputPorts: stableTypeId === 'offer.end' ? {} : { next: 'flow' },
}))
async function select(page: Page, name: string, option: RegExp) {
  await page.getByRole('combobox', { name, exact: true }).click()
  await page.getByRole('option', { name: option }).first().click()
}
async function date(page: Page, label: string, last: boolean) {
  await page.getByRole('button', { name: label, exact: true }).click()
  const dialog = page.getByRole('dialog', { name: `选择${label}` })
  const days = dialog.locator('.rdp-day_button')
  await (last ? days.last() : days.first()).click()
  await dialog.getByRole('button', { name: '确认时间' }).click()
}

test('create a real campaign type and author a complete explicit Offer graph', async ({ page }, info) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.addInitScript((real) => {
    window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true }
    if (real) sessionStorage.setItem('marketing.dev.tenantId', 'dev-tenant')
  }, live)
  let saved: GraphDefinition | undefined
  let createdType = ''
  if (live) {
    // 真联调只转发原请求至已授权本机网关，不替换响应、业务字段或身份。
    await page.route('**/api/v1/**', async (route) => {
      const request = route.request()
      const response = await route.fetch({ url: `http://127.0.0.1:18080${new URL(request.url()).pathname}${new URL(request.url()).search}` })
      await route.fulfill({ response })
    })
  } else {
    await page.route('**/api/v1/**', (route) => route.fulfill({ json: [] }))
    await page.route('**/api/client-error', (route) => route.fulfill({ status: 204 }))
    const rows: object[] = []
    await page.route('**/api/v1/campaigns', async (route) => {
      if (route.request().method() === 'GET') return route.fulfill({ json: rows })
      const body = route.request().postDataJSON(); createdType = body.campaignType
      const row = { ...body, id: 'authoring-campaign', status: 'DRAFT', createdAt: '2026-11-16T00:00:00Z' }; rows.push(row)
      await route.fulfill({ status: 201, json: row })
    })
    await page.route('**/api/v1/registries/nodes', (route) => route.fulfill({ json: registry }))
    await page.route('**/api/v1/benefits', (route) => route.fulfill({ json: [{ benefitId: 'fixture-benefit', version: 1, name: '测试权益', status: 'ACTIVE', policy: {} }] }))
    await page.route('**/api/v1/definitions/latest?*', (route) => route.fulfill({ status: 404, json: { status: 404, title: 'Not found' } }))
    await page.route('**/api/v1/definitions', (route) => {
      const body = route.request().postDataJSON(); saved = body.graph
      return route.fulfill({ status: 201, json: { definitionId: saved!.definitionId, campaignId: body.campaignId, version: 1, dialect: saved!.dialect, status: 'DRAFT' } })
    })
    await page.route('**/versions/1:validate', (route) => route.fulfill({ json: { valid: true, issues: [] } }))
    await page.route('**/versions/1:simulate', (route) => route.fulfill({ json: { subtotalMinor: 2500, discountMinor: 500, payableMinor: 2000, trace: [] } }))
  }
  await page.goto('/campaigns?create=true')
  await page.getByLabel('活动名称', { exact: true }).fill(`编辑器验收-${run}-${info.project.name}`)
  await page.getByLabel('可衡量目标', { exact: true }).fill('验证新建活动到定额优惠校验与金额试算')
  await expect(page.getByRole('combobox', { name: '活动类型' })).toContainText('标准营销')
  await page.getByRole('button', { name: '创建并进入设计' }).click()
  await expect(page).toHaveURL(/\/designers\/offer\?campaignId=/)
  await expect(page.getByRole('button', { name: /定额优惠.*offer.fixed/ })).toBeEnabled()
  if (!live) expect(createdType).toBe('STANDARD')
  const search = page.getByRole('textbox', { name: '搜索节点' })
  await search.fill('定额'); await page.getByRole('button', { name: /定额优惠.*offer.fixed/ }).click(); await search.fill('')
  await page.getByLabel('减免金额（分）', { exact: true }).fill('500')
  await date(page, '生效时间', false); await date(page, '失效时间', true)
  await select(page, '权益定义版本', /@1/)
  await page.screenshot({ path: info.outputPath('offer-config.png'), fullPage: true })
  await page.getByRole('button', { name: /结束.*offer.end/ }).click()
  await page.getByRole('button', { name: '线性大纲', exact: true }).click()
  await page.getByRole('row').filter({ hasText: 'offer.fixed' }).getByRole('button', { name: /定位节点/ }).click()
  await select(page, '出口 next', /结束/)
  await page.getByRole('button', { name: '线性大纲', exact: true }).click()
  await page.getByRole('row').filter({ hasText: 'offer.start' }).getByRole('button', { name: /定位节点/ }).click()
  await select(page, '出口 next', /定额优惠/)
  await page.getByRole('button', { name: '校验', exact: true }).click()
  await expect(page.getByRole('button', { name: '提交审核', exact: true })).toBeEnabled({ timeout: 20000 })
  if (!live) { expect(saved?.nodes).toHaveLength(3); expect(saved?.edges).toHaveLength(2); expect(saved?.nodes.find((node) => node.stableTypeId === 'offer.fixed')?.config.amountMinor).toBe('500') }
  await page.getByLabel('试算订单金额（分）', { exact: true }).fill('2500')
  await page.getByRole('button', { name: '金额试算', exact: true }).click()
  await expect(page.getByText(/优惠 500 分，应付 2000 分/)).toBeVisible({ timeout: 20000 })
  await page.screenshot({ path: info.outputPath('offer-validated.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(1)
  expect(errors).toEqual([])
})

test('referral choice is persisted and routes to the matching designer', async ({ page }) => {
  test.skip(live, '真实标准活动路径已覆盖；裂变业务验收按 S08–S10 继续')
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', (route) => route.fulfill({ json: [] }))
  let campaignType = ''
  await page.route('**/api/v1/campaigns', (route) => {
    if (route.request().method() === 'GET') return route.fulfill({ json: [] })
    const body = route.request().postDataJSON(); campaignType = body.campaignType
    return route.fulfill({ status: 201, json: { ...body, id: 'referral-created', status: 'DRAFT', createdAt: '2026-11-16T00:00:00Z' } })
  })
  await page.goto('/campaigns?create=true')
  await page.getByLabel('活动名称', { exact: true }).fill('裂变类型验收')
  await page.getByLabel('可衡量目标', { exact: true }).fill('验证真实裂变类型被保存')
  await select(page, '活动类型', /邀请有礼/)
  await page.getByRole('button', { name: '创建并进入设计' }).click()
  await expect(page).toHaveURL(/\/designers\/referral\?campaignId=referral-created/)
  expect(campaignType).toBe('REFERRAL')
})

test('author a Journey timer with elapsed routing and no fake pricing simulation', async ({ page }, info) => {
  await page.addInitScript((real) => {
    window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true }
    if (real) sessionStorage.setItem('marketing.dev.tenantId', 'dev-tenant')
  }, live)
  let campaignId = 'journey-authoring'
  let saved: GraphDefinition | undefined
  if (live) {
    const response = await page.request.post('http://127.0.0.1:18080/api/v1/campaigns', {
      headers: { 'X-Dev-Tenant-Id': 'dev-tenant', 'X-Dev-Actor-Id': 'console-admin', 'X-Dev-Organization-Ids': 'dev-tenant', 'X-Dev-Shop-Ids': 'all-shops', 'X-Dev-Permissions': '*', 'Idempotency-Key': `${run}-journey-${info.project.name}` },
      data: { name: `旅程编辑验收-${run}`, objective: '验证等待节点配置及真实校验', organizationId: 'dev-tenant', shopId: 'all-shops', campaignType: 'STANDARD' },
    })
    expect(response.ok()).toBeTruthy(); campaignId = (await response.json()).id
    await page.route('**/api/v1/**', async (route) => {
      const url = new URL(route.request().url())
      await route.fulfill({ response: await route.fetch({ url: `http://127.0.0.1:18080${url.pathname}${url.search}` }) })
    })
  } else {
    await page.route('**/api/v1/**', (route) => route.fulfill({ json: [] }))
    await page.route('**/api/v1/campaigns', (route) => route.fulfill({ json: [{ id: campaignId, name: '旅程编辑验收', objective: '等待后结束', status: 'DRAFT', campaignType: 'STANDARD', createdAt: '2026-11-16T00:00:00Z' }] }))
    await page.route('**/api/v1/registries/nodes', (route) => route.fulfill({ json: ['journey.trigger', 'journey.wait-timer', 'journey.end'].map((stableTypeId) => ({ stableTypeId, semanticVersion: '1.0.0', dialects: ['JOURNEY_STATE_MACHINE'], costWeight: 10 })) }))
    await page.route('**/api/v1/definitions/latest?*', (route) => route.fulfill({ status: 404, json: { status: 404, title: 'Not found' } }))
    await page.route('**/api/v1/definitions', (route) => {
      saved = route.request().postDataJSON().graph
      return route.fulfill({ status: 201, json: { definitionId: saved!.definitionId, campaignId, version: 1, dialect: saved!.dialect, status: 'DRAFT' } })
    })
    await page.route('**/versions/1:validate', (route) => route.fulfill({ json: { valid: true, issues: [] } }))
  }
  await page.goto(`/designers/journey?campaignId=${encodeURIComponent(campaignId)}`)
  await page.getByRole('button', { name: /等待时间.*journey.wait-timer/ }).click()
  await page.getByLabel('等待时长（秒）', { exact: true }).fill('120')
  await page.getByRole('button', { name: /结束.*journey.end/ }).click()
  await page.getByRole('button', { name: '线性大纲' }).click()
  await page.getByRole('row').filter({ hasText: 'journey.wait-timer' }).getByRole('button', { name: /定位节点/ }).click()
  await select(page, '出口 elapsed', /结束/)
  await page.getByRole('button', { name: '线性大纲' }).click()
  await page.getByRole('row').filter({ hasText: 'journey.trigger' }).getByRole('button', { name: /定位节点/ }).click()
  await select(page, '出口 next', /等待时间/)
  await page.getByRole('button', { name: '校验', exact: true }).click()
  await expect(page.getByRole('button', { name: '提交审核' })).toBeEnabled({ timeout: 20000 })
  await expect(page.getByRole('button', { name: '金额试算' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '旅程仿真', exact: true })).toBeEnabled()
  await expect(page.getByText(/仿真仅展示输入下的状态和拟生成指令/)).toBeVisible()
  if (!live) { expect(saved?.nodes.find((node) => node.stableTypeId === 'journey.wait-timer')?.config).toEqual({ delaySeconds: '120' }); expect(saved?.edges.map((edge) => edge.sourcePort).sort()).toEqual(['elapsed', 'next']) }
  await page.screenshot({ path: info.outputPath('journey-validated.png'), fullPage: true })
})
