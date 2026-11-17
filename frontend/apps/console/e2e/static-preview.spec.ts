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
