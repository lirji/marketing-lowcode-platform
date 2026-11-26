import { expect, test } from '@playwright/test'

/** JVM 隔离验收提供真实 Control 地址、活动和已准备的版本。浏览器只向该 Control 转发，请求和响应均保留。 */
test('isolated Control applies the bundle from the console', async ({ page }, info) => {
  const address = process.env.BUNDLE_CONTROL_URL
  test.skip(!address, '需要隔离 Control 验收环境')
  const campaignId = process.env.BUNDLE_CAMPAIGN_ID
  const version = Number(process.env.BUNDLE_VERSION)
  const tenant = process.env.BUNDLE_TENANT_ID
  const rawIds = process.env.BUNDLE_MANIFEST_IDS
  const manifestIds = rawIds?.split(',').map(id => id.trim())
  if (!address || !campaignId || !tenant || !Number.isSafeInteger(version) || version < 1) throw new Error('隔离验收缺少活动、版本或租户')
  const control = new URL(address)
  if (!['127.0.0.1', 'localhost', '[::1]'].includes(control.hostname)) throw new Error('Control 必须为本机隔离测试端口')
  test.setTimeout(180_000)
  await page.addInitScript(tenantId => {
    sessionStorage.setItem('marketing.dev.tenantId', tenantId)
    window.__MARKETING_CONFIG__ = { API_BASE_URL: '', AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true, DEMO_MODE: false }
  }, tenant)
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url())
    const headers = await route.request().allHeaders()
    headers['x-dev-tenant-id'] = tenant
    headers['x-dev-actor-id'] = process.env.BUNDLE_ACTOR_ID || 'console-admin'
    headers['x-dev-organization-ids'] = process.env.BUNDLE_ORGANIZATIONS || tenant
    headers['x-dev-shop-ids'] = process.env.BUNDLE_SHOPS || 'all-shops'
    const response = await route.fetch({ url: new URL(path.pathname + path.search, control).href, headers, maxRedirects: 0, timeout: 45_000 })
    await route.fulfill({ response })
  })
  await page.goto(`/releases/bundles?${new URLSearchParams({ campaignId, version: String(version) })}`)
  await expect(page.getByRole('heading', { name: '活动组合发布' })).toBeVisible()
  if (manifestIds) {
    if (manifestIds.length < 2 || manifestIds.length > 8 || manifestIds.some(id => !id)) throw new Error('请提供待创建组合的 2–8 个暂存清单')
    await page.getByRole('button', { name: '创建组合版本' }).click()
    await page.getByLabel('成员清单 ID').fill(manifestIds.join('\n'))
    await page.getByRole('button', { name: '核对清单' }).click()
    const members = page.getByRole('table', { name: '待组合清单', exact: true })
    for (const id of manifestIds) await expect(members.getByText(id, { exact: true }).or(members.getByTitle(id, { exact: true })).first()).toBeVisible()
    await page.getByRole('button', { name: `确认创建 v${version}`, exact: true }).click()
    await expect(page.getByText(`组合 v${version} 已创建`, { exact: true })).toBeVisible({ timeout: 30_000 })
    await page.screenshot({ path: info.outputPath('real-bundle-created.png'), fullPage: true })
  }
  await page.getByRole('button', { name: '准备运行端', exact: true }).click()
  const activate = page.getByRole('button', { name: `整体生效 v${version}`, exact: true })
  await expect(activate).toBeEnabled({ timeout: 45_000 })
  await activate.click()
  await page.getByRole('button', { name: '确认整体生效', exact: true }).click()
  const applied = page.getByText(`当前生效版本 v${version}`, { exact: true })
  await expect(async () => {
    if (await applied.isVisible()) return
    const resume = page.getByRole('button', { name: '继续原操作' })
    const original = page.getByRole('button', { name: '用原请求查询发布回执' })
    if (await original.isVisible() && await original.isEnabled()) await original.click()
    else if (await resume.isVisible() && await resume.isEnabled()) await resume.click()
    else {
      const query = page.getByRole('button', { name: '查询运行结果' })
      if (await query.isEnabled() && !await page.getByRole('dialog').isVisible()) await query.click()
    }
    await expect(applied).toBeVisible({ timeout: 2000 })
  }).toPass({ timeout: 120_000, intervals: [1000, 1500, 2500] })
  await expect(page.getByText('已应用', { exact: true })).toBeVisible()
  await page.screenshot({ path: info.outputPath('real-bundle-applied.png'), fullPage: true })
})
