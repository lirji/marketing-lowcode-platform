import { expect, test } from '@playwright/test'

const release = (id: string, state: string) => ({ manifest: { manifestId: id, generation: 1, cell: 'cell-a', runtime: 'journey', namespace: 'isolated-test', artifacts: [] }, state })
test('release pagination preserves loaded rows when the next page fails', async ({ page }, info) => {
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', route => route.fulfill({ json: [] }))
  await page.route('**/api/v1/approvals/page?**', route => route.fulfill({ json: { items: [], nextCursor: null, hasMore: false, status: new URL(route.request().url()).searchParams.get('status') } }))
  let fail = true
  await page.route('**/api/v1/releases/page?**', async route => {
    const query = new URL(route.request().url()).searchParams, state = query.get('state'), cursor = query.get('cursor')
    if (cursor && fail) { await route.fulfill({ status: 503, json: { type: 'about:blank', title: 'Unavailable', status: 503, detail: '分页故障验证' } }); return }
    const id = state ? 'active-only' : cursor ? 'older' : 'newer', more = !state && !cursor
    await route.fulfill({ json: { items: [release(id, state ?? 'STAGED')], nextCursor: more ? id : null, hasMore: more, state } })
  })
  await page.goto('/releases'); await page.getByRole('button', { name: '加载更多发布清单' }).click()
  await expect(page.getByText('后续发布清单加载失败')).toBeVisible({ timeout: 20000 })
  await expect(page.getByRole('button', { name: '激活 newer', exact: true })).toBeVisible()
  await page.screenshot({ path: info.outputPath('release-page-retry.png') })
  fail = false; await page.getByRole('button', { name: '重试加载更多发布清单' }).click()
  await expect(page.getByText('已加载 2 条发布清单', { exact: true })).toBeVisible()
  await page.getByRole('combobox', { name: '发布状态' }).click(); await page.getByRole('option', { name: '已激活', exact: true }).click()
  await expect(page.getByRole('button', { name: '回滚 active-only', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '激活 newer', exact: true })).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
})

test('release pages traverse the actual isolated MySQL service and preserve the older action target', async ({ page }, info) => {
  const endpoint = process.env.MARKETING_LIVE_RELEASE_ENDPOINT
  test.skip(!endpoint, '需要独占发布清单测试库与服务；浏览器仅GET')
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  const responses: { path: string; status: number }[] = [], errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/api/v1/**', async route => {
    expect(route.request().method()).toBe('GET')
    const url = new URL(route.request().url()), releaseQuery = url.pathname === '/api/v1/releases/page'
    const response = await route.fetch({ url: (releaseQuery ? endpoint : 'http://127.0.0.1:18080') + url.pathname + url.search })
    if (releaseQuery) responses.push({ path: url.pathname + url.search, status: response.status() })
    await route.fulfill({ response })
  })
  await page.goto('/releases'); await expect(page.getByText('已加载 25 条发布清单，还有更多')).toBeVisible()
  await page.getByRole('combobox', { name: '发布状态' }).click(); await page.getByRole('option', { name: '已暂存', exact: true }).click()
  await expect(page.getByRole('button', { name: '激活 release-live-staged-054', exact: true })).toBeVisible()
  await page.screenshot({ path: info.outputPath('release-live-staged.png') })
  for (const total of [50, 55]) {
    await page.getByRole('button', { name: '加载更多发布清单' }).click()
    await expect(page.getByText(`已加载 ${total} 条发布清单${total < 55 ? '，还有更多' : ''}`, { exact: true })).toBeVisible()
  }
  const table = page.getByRole('table', { name: '运行时就绪矩阵' })
  await expect(table.getByRole('row')).toHaveCount(56); await expect(table).not.toContainText('release-live-hidden')
  await page.getByRole('button', { name: '激活 release-live-staged-000', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('激活 release-live-staged-000')
  await page.screenshot({ path: info.outputPath('release-live-older-target.png') })
  await page.getByRole('dialog').getByRole('button', { name: '取消', exact: true }).click()
  await page.getByRole('combobox', { name: '发布状态' }).click(); await page.getByRole('option', { name: '已激活', exact: true }).click()
  await expect(page.getByRole('button', { name: '回滚 release-live-active-054', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '激活 release-live-staged-000', exact: true })).toHaveCount(0)
  expect(responses.every(row => row.status === 200)).toBe(true); expect(errors).toEqual([])
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
  await info.attach('release-api-receipts', { body: JSON.stringify(responses), contentType: 'application/json' })
  await page.unrouteAll({ behavior: 'wait' })
})
