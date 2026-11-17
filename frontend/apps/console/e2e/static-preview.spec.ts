import { expect, test } from '@playwright/test'

test('static dashboard and operations show connection states without business requests', async ({ page }, info) => {
  const requests: string[] = []
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: true, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', route => { requests.push(route.request().url()); return route.fulfill({ status: 500, json: {} }) })
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '查看当前运营数据' })).toBeVisible()
  await expect(page.getByText('静态预览模式', { exact: true })).toBeVisible()
  await expect(page.getByRole('region', { name: '核心指标' }).getByText('—', { exact: true })).toHaveCount(4)
  await expect(page.getByText('收入序列需要连接服务')).toBeVisible()
  await expect(page.getByText('发布状态需要连接服务')).toBeVisible()
  await expect(page.getByRole('table')).toHaveCount(0)
  await page.screenshot({ path: info.outputPath('dashboard-static-preview.png'), fullPage: true })
  await page.goto('/operations?campaignId=untrusted-deep-link')
  const cases = [
    ['全链路查询', '营销轨迹需要连接服务'], ['Journey 实例', 'Journey 实例需要连接服务'],
    ['触达记录', '触达记录需要连接服务'], ['发放', '演示模式不查询真实发放指令'],
    ['DLQ / 隔离区', '隔离消息需要连接服务'], ['资金对账', '资金对账需要连接服务'],
  ]
  for (const [tab, notice] of cases) {
    await page.getByRole('tab', { name: tab, exact: true }).click()
    await expect(page.getByText(notice, { exact: true })).toBeVisible()
    await expect(page.getByRole('table')).toHaveCount(0)
    await expect(page.getByText(/本货主资金守恒成立|未发现差异|CATEGORY_MATCH|^PASS$/)).toHaveCount(0)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
  }
  await page.screenshot({ path: info.outputPath('operations-static-preview.png'), fullPage: true })
  expect(requests).toEqual([])
  expect(errors).toEqual([])
})

test('static asset tabs and referral analysis never read or refresh business records', async ({ page }, info) => {
  const requests: string[] = []
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: true, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', route => { requests.push(route.request().url()); return route.fulfill({ status: 500, json: {} }) })
  await page.goto('/assets')
  for (const [tab, title] of [['Audience 快照', '人群列表需要连接服务'], ['权益与券', '权益列表需要连接服务'], ['消息模板', '模板列表需要连接服务'], ['字段与节点', '字段与节点需要连接服务']]) {
    await page.getByRole('tab', { name: tab, exact: true }).click()
    await expect(page.getByText(title, { exact: true })).toBeVisible()
    await expect(page.getByRole('table')).toHaveCount(0)
    await expect(page.getByRole('button', { name: /^刷新/ })).toHaveCount(0)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
  }
  await page.screenshot({ path: info.outputPath('assets-static-preview.png'), fullPage: true })
  await page.goto('/referral-operations?campaignId=untrusted-deep-link')
  for (const tab of ['参与人', '关系 / 资格', '奖励', '异常']) {
    await page.getByRole('tab', { name: tab, exact: true }).click()
    await expect(page.getByRole('table')).toHaveCount(0)
  }
  await page.goto('/analytics')
  await expect(page.getByText('请连接演示数据源', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '重算全部归因', exact: true })).toBeDisabled()
  expect(requests).toEqual([])
})
