import { expect, test } from '@playwright/test'

test('benefit assets search across pages and recover the failed next page', async ({ page }, info) => {
  const rows = Array.from({ length: 62 }, (_, index) => ({ benefitId: `benefit-${String(index).padStart(3, '0')}`, version: 2,
    name: index === 61 ? '最后一页目标权益' : `可见权益 ${index}`, status: index === 61 ? 'RETIRED' : 'ACTIVE',
    benefitSkuId: index === 61 ? 'sku-last' : `sku-${index}`, createdAt: '2026-10-18T16:30:00Z' }))
  let allowNext = false
  const queries: { q: string; cursor: string | null }[] = [], errors: string[] = [], writes: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  page.on('request', request => { if (request.url().includes('/api/v1/') && !new URL(request.url()).pathname.endsWith('/client-errors') && request.method() !== 'GET') writes.push(request.url()) })
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', async route => {
    if (new URL(route.request().url()).pathname.endsWith('/client-errors')) { await route.fulfill({ status: 204 }); return }
    const url = new URL(route.request().url()), path = url.pathname
    if (path.endsWith('/benefits/catalog/page')) {
      const q = url.searchParams.get('q') ?? '', cursor = url.searchParams.get('cursor'); queries.push({ q, cursor })
      expect(url.searchParams.get('limit')).toBe('25')
      if (cursor && !allowNext) { await route.fulfill({ status: 503, json: { code: 'DEPENDENCY_UNAVAILABLE', detail: '暂时无法读取下一页' } }); return }
      const matching = q ? [rows[61]] : rows
      const start = cursor ? matching.findIndex(row => row.benefitId === cursor) + 1 : 0
      const items = matching.slice(start, start + 25), hasMore = matching.length > start + items.length
      await route.fulfill({ json: { items, hasMore, nextCursor: hasMore ? items.at(-1)!.benefitId : null, query: q } }); return
    }
    if (path.endsWith('/audiences/page')) { await route.fulfill({ json: { items: [], nextCursor: null, hasMore: false, query: '' } }); return }
    if (path.endsWith('/releases/page')) { await route.fulfill({ json: { items: [], nextCursor: null, hasMore: false, state: url.searchParams.get('state') } }); return }
    if (path.endsWith('/approvals/page')) { await route.fulfill({ json: { items: [], nextCursor: null, hasMore: false, status: url.searchParams.get('status') } }); return }
    await route.fulfill({ json: [] })
  })
  await page.goto('/assets')
  await page.getByRole('tab', { name: '权益与券' }).click()
  await expect(page.getByText(/已加载 25 项/)).toBeVisible()
  const search = page.getByRole('searchbox', { name: '搜索权益' })
  await search.fill('sku-last')
  await expect(page.getByRole('cell', { name: '最后一页目标权益', exact: true })).toBeVisible()
  expect(queries.at(-1)).toEqual({ q: 'sku-last', cursor: null })
  await page.getByRole('button', { name: '查看最后一页目标权益' }).click()
  await expect(page.getByRole('dialog')).toContainText('2026/10/19 00:30:00')
  await expect(page.getByRole('dialog')).toContainText('已退役')
  await page.screenshot({ path: info.outputPath('benefit-full-search-detail.png') })
  await page.getByRole('button', { name: '关闭详情' }).click()
  await expect(search).toHaveValue('sku-last')
  await search.fill('')
  await expect(page.getByText(/已加载 25 项/)).toBeVisible()
  await page.getByRole('button', { name: '加载更多权益' }).click()
  await expect(page.getByText('下一页权益加载失败', { exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: '可见权益 0', exact: true })).toBeVisible()
  allowNext = true
  await page.getByRole('button', { name: '重试下一页权益' }).click()
  await expect(page.getByText(/已加载 50 项/)).toBeVisible()
  await page.getByRole('button', { name: '加载更多权益' }).click()
  await expect(page.getByText(/已加载 62 项/)).toHaveText(/当前搜索已全部加载/)
  await page.getByRole('cell', { name: '最后一页目标权益', exact: true }).scrollIntoViewIfNeeded()
  await page.screenshot({ path: info.outputPath('benefit-last-page.png') })
  await expect(page.getByRole('link', { name: '编辑最后一页目标权益' })).toHaveAttribute('href', '/designers/benefit?benefitId=benefit-061')
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
  expect(errors).toEqual([]); expect(writes).toEqual([])
})
