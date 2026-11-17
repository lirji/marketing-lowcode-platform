import { expect, test } from '@playwright/test'

test('release rollback targets and uncertain command recovery stay usable', async ({ page }, info) => {
  const sourceId = '85441433-eca9-4f49-ade5-b8fe795adf86'
  const targetId = 'fc72f401-7d94-45fd-96fb-846afdbb2301'
  const artifact = { artifactId: 'journey-plan', type: 'JOURNEY_PLAN', checksum: 'sha256:test', sourceDigest: 'sha256:source', signatureKeyId: 'key', signature: 'signed', abi: 'marketing-journey-plan/1', definitionId: 'journey-retained-version', definitionVersion: 2 }
  const manifest = (id: string, generation: number) => ({ manifestId: id, generation, stableGeneration: generation, environment: 'test', cell: 'cell-a', runtime: 'journey', namespace: 'main', artifacts: [artifact], signature: 'signed' })
  let rollbackCalls = 0
  const keys: string[] = []
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/client-error', (route) => route.fulfill({ status: 204 }))
  await page.route('**/api/v1/approvals/page?**', route => route.fulfill({ json: { items: [], hasMore: false, nextCursor: null, status: new URL(route.request().url()).searchParams.get('status') } }))
  await page.route('**/api/v1/releases/page?**', (route) => {
    const state = new URL(route.request().url()).searchParams.get('state')
    return route.fulfill({ json: { items: [
    { manifest: manifest('staged-newer', 10), state: 'STAGED', readyReplicas: 1, readyCapacity: 2 },
    { manifest: manifest(sourceId, 9), state: rollbackCalls ? 'ROLLED_BACK' : 'ACTIVE', readyReplicas: 1, readyCapacity: 2 },
  ].filter(item => !state || item.state === state), nextCursor: null, hasMore: false, state } })
  })
  await page.route(`**/api/v1/releases/${sourceId}/rollback-candidates`, (route) => rollbackCalls
    ? route.fulfill({ status: 409, json: { type: 'about:blank', title: 'Conflict', status: 409, code: 'ROLLBACK_SOURCE_NOT_ACTIVE', detail: '源清单已回滚' } })
    : route.fulfill({ json: [{ manifestId: targetId, generation: 4, expiresAt: '2027-01-01T00:00:00Z', artifacts: [artifact] }] }))
  await page.route(`**/api/v1/releases/${sourceId}:rollback`, async (route) => {
    expect(route.request().postDataJSON()).toEqual({ targetGeneration: 4 })
    keys.push(route.request().headers()['idempotency-key']); rollbackCalls++
    if (rollbackCalls === 1) await route.fulfill({ status: 503, json: { type: 'about:blank', title: 'Response lost', status: 503, detail: '回执暂不可用' } })
    else await route.fulfill({ json: { manifest: manifest(targetId, 4), state: 'ACTIVE', readyReplicas: 1, readyCapacity: 2 } })
  })
  await page.goto('/releases')
  const sourceButton = page.getByRole('button', { name: `回滚 ${sourceId}`, exact: true })
  await sourceButton.click()
  await expect(page.getByRole('button', { name: '确认执行' })).toBeDisabled()
  await page.getByRole('combobox', { name: '回滚目标版本' }).click()
  await page.getByRole('option', { name: `第 4 代 · ${targetId}` }).click()
  await expect(page.getByRole('table', { name: '回滚目标详情', exact: true }).getByText('journey-retained-version')).toBeVisible()
  await page.getByRole('checkbox').check()
  await page.screenshot({ path: info.outputPath('rollback-selected.png'), fullPage: true })
  await page.getByRole('button', { name: '确认执行' }).click()
  await expect(page.getByText('上次回滚结果待确认', { exact: true })).toBeVisible()
  await page.evaluate(() => { window.dispatchEvent(new Event('offline')); window.dispatchEvent(new Event('online')) })
  await expect(page.getByText('源清单已回滚', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '确认执行' })).toBeDisabled()
  await page.screenshot({ path: info.outputPath('rollback-uncertain.png'), fullPage: true })
  await page.getByRole('button', { name: '重试原回滚请求' }).click()
  await expect(page.getByText(`回滚已提交 ${targetId}`, { exact: true })).toBeVisible()
  expect(keys).toHaveLength(2); expect(keys[0]).toBeTruthy(); expect(keys[0]).toEqual(keys[1])
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await page.getByRole('button', { name: '熔断 staged-newer 的命名空间' }).click()
  await expect(page.getByText(/不限于这条清单的环境/)).toBeVisible()
  await page.getByLabel('操作原因').pressSequentially('隔离环境核对原因')
  await expect(page.getByLabel('操作原因')).toHaveValue('隔离环境核对原因')
  await page.screenshot({ path: info.outputPath('kill-scope.png'), fullPage: true })
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await expect(page.getByRole('button', { name: '熔断 staged-newer 的命名空间' })).toBeFocused()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
})
