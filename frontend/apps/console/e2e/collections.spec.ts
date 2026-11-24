import { expect, test } from '@playwright/test'
import { campaignFixtures } from '../src/test/fixtures/campaigns'

test('campaign filters survive modal close and design menu remains keyboard accessible', async ({ page }) => {
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/campaigns', route => route.fulfill({ json: campaignFixtures }))
  await page.route('**/api/v1/definitions/latest?**', route => route.fulfill({ status: 404, json: { status: 404, title: 'No saved definition' } }))
  await page.goto('/campaigns?q=unmatched-search&status=DRAFT&create=true')
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await expect(page.getByRole('searchbox', { name: '搜索活动' })).toHaveValue('unmatched-search')
  await expect(page.getByRole('combobox', { name: '状态筛选' })).toHaveText('草稿')
  await expect(page).toHaveURL(/q=unmatched-search&status=DRAFT/)
  await page.getByRole('button', { name: '清除筛选', exact: true }).first().click()
  const trigger = page.getByRole('button', { name: /^设计：/ }).first()
  await trigger.focus()
  await trigger.press('ArrowDown')
  await expect(page.getByRole('menuitem', { name: 'Offer 规则' })).toBeFocused()
  await page.getByRole('menuitem', { name: 'Offer 规则' }).press('End')
  await expect(page.getByRole('menuitem', { name: 'Journey 旅程', exact: true })).toBeFocused()
  await page.getByRole('menuitem', { name: 'Journey 旅程', exact: true }).press('Escape')
  await expect(trigger).toBeFocused()
  await trigger.click()
  const menu = await page.getByRole('menu').boundingBox()
  expect(menu).not.toBeNull()
  expect(menu!.x).toBeGreaterThanOrEqual(0)
  expect(menu!.x + menu!.width).toBeLessThanOrEqual(page.viewportSize()!.width)
  await page.getByRole('menuitem', { name: 'Journey 旅程' }).click()
  await expect(page).toHaveURL(/designers\/journey\?campaignId=/)
})

test('asset details show complete values and tabs support keyboard navigation', async ({ page }) => {
  await page.addInitScript(() => {
    window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true }
  })
  await page.route('**/api/v1/**', route => route.fulfill({ json: new URL(route.request().url()).pathname.endsWith('/audiences/page') ? { items: [], nextCursor: null, hasMore: false, query: '' } : [] }))
  await page.route('**/api/v1/benefits/catalog/page?**', (route) => route.fulfill({ json: { query: new URL(route.request().url()).searchParams.get('q') ?? '', nextCursor: null, hasMore: false, items: [
    { benefitId: 'coupon-live', version: 2, name: '测试权益完整名称', status: 'DRAFT', resourceKey: '', benefitSkuId: 'sku-complete-reference-123456789', policy: {} },
  ] } }))
  await page.goto('/assets')
  await page.getByRole('tab', { name: 'Audience 快照' }).focus()
  await page.getByRole('tab', { name: 'Audience 快照' }).press('ArrowRight')
  await expect(page.getByRole('tabpanel', { name: '权益与券' })).toBeVisible()
  await page.getByRole('searchbox', { name: '搜索权益' }).fill('coupon-live')
  const view = page.getByRole('button', { name: '查看测试权益完整名称' })
  const edit = page.getByRole('link', { name: '编辑测试权益完整名称' })
  await expect(view).toBeInViewport()
  await expect(edit).toBeInViewport()
  const viewBox = (await view.boundingBox())!
  const editBox = (await edit.boundingBox())!
  expect(Math.min(viewBox.height, editBox.height)).toBeGreaterThanOrEqual(32)
  if (page.viewportSize()!.width <= 720) {
    expect(editBox.y - (viewBox.y + viewBox.height)).toBeGreaterThanOrEqual(8)
    await page.getByRole('region', { name: '权益表格' }).evaluate(table => { table.scrollLeft = table.scrollWidth })
    await expect(view).toBeInViewport()
    await expect(edit).toBeInViewport()
  } else {
    expect(editBox.x - (viewBox.x + viewBox.width)).toBeGreaterThanOrEqual(8)
  }
  await expect(edit).toHaveAttribute('href', '/designers/benefit?benefitId=coupon-live')
  await view.click()
  await expect(page.getByRole('dialog')).toContainText('sku-complete-reference-123456789')
  await page.getByRole('button', { name: '关闭详情' }).press('Escape')
  await expect(view).toBeFocused()
  await expect(page.getByRole('searchbox', { name: '搜索权益' })).toHaveValue('coupon-live')
})

test('shared page layouts contain horizontal scrolling inside their content', async ({ page }, testInfo) => {
  const paths = ['/campaigns', '/assets', '/governance', '/releases', '/operations', '/analytics', '/referral-operations', '/designers/offer', '/designers/journey', '/designers/audience', '/designers/benefit', '/designers/referral', '/designers/dmn']
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  for (const path of paths) {
    await page.goto(path)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await expect(page.getByText('正在加载工作台…')).toHaveCount(0)
    // 宽表和画布可在自己的容器滚动，不能把整页撑出屏幕。
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
    expect(overflow, `${path} should not overflow the viewport`).toBeLessThanOrEqual(1)
    await page.screenshot({ path: testInfo.outputPath(`${path.slice(1).replaceAll('/', '-')}.png`), fullPage: false })
  }
  expect(errors).toEqual([])
})
