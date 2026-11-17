import { expect, test } from '@playwright/test'

test('template catalog searches all pages, refreshes after save and recovers a failed next page', async ({ page }, info) => {
  const rows = Array.from({ length: 62 }, (_, index) => ({ templateId: `template-${String(index).padStart(3, '0')}`, version: 2, channel: 'SMS',
    content: index === 61 ? '最后模板完整正文 {{coupon_needle}}' : '原有模板正文', requiredVariables: index === 61 ? ['zeta', 'coupon_needle'] : [], state: 'APPROVED', createdAt: '2026-10-18T16:30:00Z' }))
  let allowNext = false
  const errors: string[] = [], writes: string[] = [], queries: string[] = []
  page.on('pageerror', error => errors.push(error.message)); page.on('request', req => { if (req.url().includes('/api/v1/') && !new URL(req.url()).pathname.endsWith('/client-errors') && req.method() !== 'GET') writes.push(new URL(req.url()).pathname) })
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', async route => {
    if (new URL(route.request().url()).pathname.endsWith('/client-errors')) { await route.fulfill({ status: 204 }); return }
    const url = new URL(route.request().url()), path = url.pathname
    if (path.endsWith('/templates/catalog/page')) {
      const q = url.searchParams.get('q') ?? '', cursor = url.searchParams.get('cursor'); queries.push(q)
      if (cursor && !allowNext) { await route.fulfill({ status: 503, json: { code: 'DEPENDENCY_UNAVAILABLE', detail: '下一页暂时不可用' } }); return }
      const source = q ? [rows[61]] : rows, start = cursor ? source.findIndex(item => item.templateId === cursor) + 1 : 0
      const items = source.slice(start, start + 25), hasMore = source.length > start + items.length
      await route.fulfill({ json: { items, nextCursor: hasMore ? items.at(-1)!.templateId : null, hasMore, query: q } }); return
    }
    if (path.endsWith('/templates') && route.request().method() === 'POST') {
      const body = route.request().postDataJSON(); expect(body.templateId).toBe(rows[61].templateId); expect(body.version).toBe(3)
      rows[61] = { ...rows[61], ...body }; await route.fulfill({ status: 201, json: rows[61] }); return
    }
    if (path.endsWith('/audiences/page')) { await route.fulfill({ json: { items: [], nextCursor: null, hasMore: false, query: '' } }); return }
    if (path.endsWith('/releases/page')) { await route.fulfill({ json: { items: [], nextCursor: null, hasMore: false, state: url.searchParams.get('state') } }); return }
    await route.fulfill({ json: [] })
  })
  await page.goto('/assets'); await page.getByRole('tab', { name: '消息模板' }).click(); await expect(page.getByText(/已加载 25 项；按模板/)).toBeVisible()
  const search = page.getByRole('searchbox', { name: '搜索消息模板' }); await search.fill('coupon_needle')
  await expect(page.getByRole('cell', { name: 'template-061', exact: true })).toBeVisible(); expect(queries).toContain('coupon_needle')
  await page.getByRole('button', { name: '查看模板 template-061' }).click(); await expect(page.getByRole('dialog')).toContainText('最后模板完整正文'); await expect(page.getByRole('dialog')).toContainText('zeta, coupon_needle')
  await page.screenshot({ path: info.outputPath('template-page-details.png') }); await page.getByRole('button', { name: '关闭详情' }).click()
  await page.getByRole('button', { name: '编辑模板 template-061 新版本' }).click(); await page.getByRole('button', { name: '保存新版本', exact: true }).click()
  await expect(page.getByText('模板版本已保存', { exact: true })).toBeVisible(); await expect(page.getByRole('cell', { name: 'v3', exact: true })).toBeVisible(); await expect(search).toHaveValue('coupon_needle')
  await search.fill(''); await expect(page.getByText(/已加载 25 项；按模板/)).toBeVisible(); await page.getByRole('button', { name: '加载更多模板', exact: true }).click()
  await expect(page.getByText('下一页模板加载失败', { exact: true })).toBeVisible(); await expect(page.getByRole('cell', { name: 'template-000', exact: true })).toBeVisible()
  allowNext = true; await page.getByRole('button', { name: '重试下一页模板' }).click(); await expect(page.getByText(/已加载 50 项；按模板/)).toBeVisible(); await page.getByRole('button', { name: '加载更多模板', exact: true }).click()
  await expect(page.getByText(/已加载 62 项；按模板/)).toContainText('当前搜索已全部加载'); await page.getByRole('cell', { name: 'template-061', exact: true }).scrollIntoViewIfNeeded(); await page.screenshot({ path: info.outputPath('template-last-page.png') })
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width + 1); expect(errors).toEqual([]); expect(writes).toEqual(['/api/v1/templates'])
})
