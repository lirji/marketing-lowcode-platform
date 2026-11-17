import { expect, test } from '@playwright/test'

test('browser error handling reports safe authenticated diagnostics without recursion', async ({ page }) => {
  const collected: { body: unknown; headers: Record<string, string> }[] = []
  const writes: string[] = []
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    if (request.method() !== 'GET') writes.push(path)
    if (path.endsWith('/client-errors')) {
      collected.push({ body: request.postDataJSON(), headers: request.headers() })
      await route.fulfill({ status: 503, json: { code: 'GATEWAY_RATE_LIMITER_UNAVAILABLE' } }); return
    }
    if (path.endsWith('/audiences/page')) { await route.fulfill({ json: { items: [], nextCursor: null, hasMore: false, query: '' } }); return }
    if (path.endsWith('/releases/page')) { await route.fulfill({ json: { items: [], nextCursor: null, hasMore: false, state: new URL(request.url()).searchParams.get('state') } }); return }
    await route.fulfill({ json: [] })
  })
  await page.goto('/assets?token=private-query')
  await expect(page.getByRole('tab', { name: '消息模板' })).toBeVisible()
  await page.evaluate(() => window.dispatchEvent(new ErrorEvent('error', { message: 'Bearer secret-message', error: new Error('member-id-secret') })))
  await expect.poll(() => collected.length).toBe(1)
  expect(collected[0].body).toEqual({ schemaVersion: 1, source: 'WINDOW', route: 'ASSETS', httpStatus: 0 })
  expect(collected[0].headers['x-dev-tenant-id']).toBeTruthy()
  expect(collected[0].headers['x-dev-actor-id']).toBeTruthy()
  expect(JSON.stringify(collected[0].body)).not.toMatch(/secret|private|token/)
  // 同一同步批次触发足量事件；入口503也只能消费原有10次预算，不能产生额外重试。
  await page.evaluate(() => { for (let i = 0; i < 20; i++) window.dispatchEvent(new ErrorEvent('error', { message: 'private' })) })
  await expect.poll(() => collected.length).toBe(10)
  await page.getByRole('tab', { name: 'Audience 快照', exact: true }).click()
  expect(collected).toHaveLength(10)
  expect(writes).toEqual(Array(10).fill('/api/v1/client-errors'))
})
