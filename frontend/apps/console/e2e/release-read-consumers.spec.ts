import { expect, test } from '@playwright/test'

// 独立HTTP合同样例只验证交互，不向真实控制面发送编译、暂存或ACK。
test('wizard follows its staged ID outside the list window and refuses stale readiness on failure', async ({ page }, info) => {
  const target = 'older-than-list-window'
  const artifact = { artifactId: 'journey-artifact', type: 'JOURNEY_PLAN', checksum: 'sha256:test', sourceDigest: 'sha256:source', signatureKeyId: 'test-key', signature: 'test-signature', abi: 'marketing-journey-plan/1', definitionId: 'journey-older', definitionVersion: 1 }
  const staged = { manifest: { manifestId: target, cell: 'cell-a', environment: 'local', runtime: 'journey', namespace: 'main', generation: 1, artifacts: [artifact] }, state: 'STAGED', readyReplicas: 0, readyCapacity: 0 }
  let failure = false, detailReads = 0, forbiddenWrites = 0, legacyReads = 0
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/client-error', route => route.fulfill({ status: 204 }))
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname
    if (path.endsWith(':ack') || path.endsWith(':activate')) { forbiddenWrites++; await route.fulfill({ status: 500 }); return }
    if (path === '/api/v1/releases/page') {
      const state = url.searchParams.get('state')
      const items = Array.from({ length: 25 }, (_, index) => ({ ...staged, state: state ?? 'STAGED', manifest: { ...staged.manifest, manifestId: `recent-${index}` } }))
      await route.fulfill({ json: { items, hasMore: true, nextCursor: 'recent-24', state } }); return
    }
    if (path === '/api/v1/approvals/page') {
      const status = url.searchParams.get('status')
      await route.fulfill({ json: { items: status === 'APPROVED' ? [{ caseId: 'approved-older', definitionId: 'journey-older', definitionVersion: 1, submittedBy: 'author', requiredRoles: ['BUSINESS'], approvals: { BUSINESS: 'APPROVE' }, status, updatedAt: '2026-11-01T00:00:00Z' }] : [], hasMore: false, nextCursor: null, status } }); return
    }
    if (path.includes('/definitions/journey-older/')) {
      await route.fulfill({ json: { definitionId: 'journey-older', campaignId: 'campaign', version: 1, dialect: 'JOURNEY_STATE_MACHINE', status: 'APPROVED', graph: { definitionId: 'journey-older', dialect: 'JOURNEY_STATE_MACHINE', dialectVersion: '1', nodes: [], edges: [], variables: {}, annotations: {} } } }); return
    }
    if (path === '/api/v1/compile') { await route.fulfill({ json: { ...artifact, valid: true, messages: [] } }); return }
    if (path === '/api/v1/artifacts/journey-artifact') { await route.fulfill({ json: { ...artifact, payload: '{}', compiledAt: '2026-11-01T00:00:00Z' } }); return }
    if (path === '/api/v1/releases') {
      if (request.method() === 'GET') legacyReads++
      await route.fulfill({ json: request.method() === 'POST' ? staged : [] }); return
    }
    if (path === `/api/v1/releases/${target}`) {
      detailReads++
      await route.fulfill(failure ? { status: 503, json: { type: 'about:blank', title: 'Unavailable', status: 503, detail: '清单读取暂不可用' } } : { json: { ...staged, readyReplicas: 1, readyCapacity: 7 } }); return
    }
    await route.fulfill({ json: [] })
  })
  await page.goto('/releases')
  await page.getByRole('button', { name: '编译并暂存', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('combobox', { name: '批准单 / 冻结版本' }).click()
  await page.getByRole('option', { name: /approved-older/ }).click()
  await dialog.getByRole('button', { name: '编译', exact: true }).click()
  await expect(dialog.getByText('制品已读取', { exact: true })).toBeVisible()
  await dialog.getByRole('button', { name: '暂存', exact: true }).click()
  await expect(dialog.getByRole('button', { name: '进入激活确认' })).toBeEnabled()
  expect(detailReads).toBeGreaterThan(0)
  await dialog.getByRole('button', { name: '进入激活确认' }).scrollIntoViewIfNeeded()
  await page.screenshot({ path: info.outputPath('release-id-ready.png') })
  failure = true
  await dialog.getByRole('button', { name: '刷新状态' }).click()
  await expect(dialog.getByText('清单状态读取失败', { exact: true })).toBeVisible({ timeout: 20000 })
  await expect(dialog.getByRole('button', { name: '进入激活确认' })).toBeDisabled()
  await dialog.getByRole('button', { name: '进入激活确认' }).scrollIntoViewIfNeeded()
  await page.screenshot({ path: info.outputPath('release-id-failed.png') })
  expect(legacyReads).toBe(0); expect(forbiddenWrites).toBe(0); expect(errors).toEqual([])
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
})
