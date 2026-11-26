import { expect, test, type Route } from '@playwright/test'
import { campaignFixtures } from '../src/test/fixtures/campaigns'

test('campaign bundle creation, scheduling, uncertain activation and whole rollback', async ({ page }, info) => {
  const campaignId = campaignFixtures[0].id
  const now = new Date().toISOString()
  const far = new Date(Date.now() + 100 * 86400000).toISOString()
  const names = ['decision', 'journey']
  const manifest = (runtime: string, v: number) => ({ manifestId: `${runtime}-${v}`, runtime, namespace: 'main',
    environment: 'acceptance', cell: 'zone-1', generation: v, expiresAt: far, canaryBasisPoints: 0, artifacts: [{
      artifactId: `asset-${runtime}-${v}`, type: runtime, checksum: 'sha256:sum', sourceDigest: 'sha256:source',
      signatureKeyId: 'test', signature: 'sig', abi: 'marketing/1', definitionId: `definition-${runtime}`, definitionVersion: v,
    }] })
  const bundle = (v: number) => ({ bundleId: `bundle-${v}`, campaignId, version: v, environment: 'acceptance', cell: 'zone-1',
    state: 'PREPARING', contentHash: `hash-${v}`, members: names.map(name => manifest(name, v)), createdBy: 'tester', createdAt: now })
  let head = 1, appliedVersion = 1, appliedSequence = 7, ready = false
  let pending: { operationId: string; campaignId: string; version: number; action: string; activationSequence: number; state: string; reasonCode: string | null; createdAt: string; updatedAt: string } | null = null
  let scheduled: { scheduleId: string; campaignId: string; version: number; expectedAppliedSequence: number; revision: number; dueAt: string; applyBefore: string; state: string; reasonCode: null; operationId: null; createdAt: string; updatedAt: string } | null = null
  const manifestNumbers = { decision: 'MKT-MNF-261019-0000000001', journey: 'MKT-MNF-261019-0000000002' }
  let failPrepare = true
  const keys: string[] = [], input: unknown[] = []
  const base = `/api/v1/campaigns/${campaignId}/release-bundles`
  const error = (route: Route, status: number, code: string, detail: string) =>
    route.fulfill({ status, json: { type: 'about:blank', title: '请求失败', status, code, detail } })
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', async route => {
    const req = route.request(), url = new URL(req.url()), path = url.pathname
    const json = (value: unknown) => route.fulfill({ json: value })
    if (path === '/api/v1/campaigns') return json(campaignFixtures.map(item => ({ ...item, campaignType: 'STANDARD' })))
    if (path === '/api/v1/approvals/page') return json({ items: [], nextCursor: null, hasMore: false, status: url.searchParams.get('status') })
    if (path.startsWith('/api/v1/releases/')) {
      const member = names.find(name => path.endsWith(`/${manifestNumbers[name as keyof typeof manifestNumbers]}`))
      if (member) return json({ state: 'STAGED', manifest: manifest(member, 2), businessNo: manifestNumbers[member as keyof typeof manifestNumbers] })
    }
    if (!path.startsWith(base)) return json([])
    const part = path.slice(base.length)
    if (part === '/latest') return json(bundle(head))
    if (part === '') {
      expect(req.postDataJSON()).toEqual({ expectedVersion: 1, manifestIds: ['decision-2', 'journey-2'] })
      if (failPrepare) { failPrepare = false; return error(route, 409, 'RELEASE_BUNDLE_MEMBER_UNAVAILABLE', '审批回执待核对') }
      head = 2; return json(bundle(2))
    }
    if (part === '/operations' && req.method() === 'GET') return json({ appliedVersion, appliedSequence, latestOperation: pending,
      rollbackEligible: appliedVersion === 2 && url.searchParams.get('version') === '1' })
    if (part === '/operations' && req.method() === 'POST') {
      const body = req.postDataJSON()
      if (body.action === 'ACTIVATE') {
        keys.push(req.headers()['idempotency-key']); input.push(body)
        expect(body).toEqual({ version: 2, expectedAppliedSequence: 7, action: 'ACTIVATE' })
        pending = { operationId: 'operation-8', campaignId, ...body, state: 'PENDING', activationSequence: 8, reasonCode: 'RELEASE_BUNDLE_PROBE_PENDING', createdAt: now, updatedAt: now }
        if (keys.length === 1) return error(route, 503, 'UPSTREAM_TIMEOUT', '运行端回执连接中断')
      } else {
        expect(body).toEqual({ version: 1, expectedAppliedSequence: 8, action: 'ROLLBACK' })
        appliedVersion = 1; appliedSequence = 9
        pending = { operationId: 'operation-9', campaignId, ...body, state: 'APPLIED', activationSequence: 9, reasonCode: null, createdAt: now, updatedAt: now }
      }
      return json(pending)
    }
    if (pending && part === `/operations/${pending.operationId}:retry`) {
      appliedVersion = pending.version; appliedSequence = pending.activationSequence; pending.state = 'APPLIED'; pending.reasonCode = null
      return json(pending)
    }
    if (pending && part === `/operations/${pending.operationId}`) return json(pending)
    if (part === '/schedules' && req.method() === 'POST') {
      const body = req.postDataJSON()
      expect(body.version).toBe(2); expect(body.expectedAppliedSequence).toBe(7); expect(Date.parse(body.dueAt)).toBeGreaterThan(Date.now())
      scheduled = { scheduleId: 'schedule-next', campaignId, ...body, revision: 1, applyBefore: far, state: 'SCHEDULED', operationId: null, reasonCode: null, createdAt: now, updatedAt: now }
      return json(scheduled)
    }
    if (part === '/schedules') return json(scheduled && +url.searchParams.get('version')! === scheduled.version ? [scheduled] : [])
    if (scheduled && part.startsWith(`/schedules/${scheduled.scheduleId}:`)) {
      expect(url.searchParams.get('expectedRevision')).toBe(String(scheduled.revision))
      scheduled.revision++; scheduled.state = part.endsWith(':pause') ? 'PAUSED' : 'SCHEDULED'
      return json(scheduled)
    }
    const match = /^\/versions\/(\d+)(.*)$/.exec(part)
    if (match) {
      const version = Number(match[1])
      if (!match[2]) return json(bundle(version))
      if (match[2] === ':prepare-runtime') ready = true
      return json({ bundleId: `bundle-${version}`, version, contentHash: `hash-${version}`, runtimeReady: ready, checkedAt: now, expiresAt: far,
        members: names.map(name => ({ manifestId: `${name}-${version}`, runtime: name, namespace: 'main', runtimeReady: ready, readyReplicas: ready ? 2 : 0, readyCapacity: ready ? 5 : 0, ignoredEvidence: 0 })) })
    }
    throw new Error(`Unexpected route ${part}`)
  })
  await page.goto('/campaigns')
  await page.getByRole('button', { name: '组合发布', exact: true }).first().click()
  await expect(page.getByText('当前生效版本 v1', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '创建组合版本' }).click()
  await page.getByLabel('成员清单 ID').fill(Object.values(manifestNumbers).join('\n'))
  await page.getByRole('button', { name: '核对清单' }).click()
  const create = page.getByRole('button', { name: '确认创建 v2' })
  await create.click()
  await expect(page.getByText('审批回执待核对')).toBeVisible()
  await expect(page.getByLabel('成员清单 ID')).toHaveValue(Object.values(manifestNumbers).join('\n'))
  await create.click()
  await expect(page).toHaveURL(/version=2/)
  const activate = page.getByRole('button', { name: '整体生效 v2' })
  await expect(activate).toBeDisabled()
  await page.getByRole('button', { name: '准备运行端' }).click()
  await expect(activate).toBeEnabled()
  await page.getByRole('button', { name: '计划生效时间', exact: true }).click()
  const picker = page.getByLabel('选择计划生效时间')
  await picker.locator('.rdp-button_next').click()
  await picker.locator('.rdp-day_button').first().click()
  await picker.getByRole('button', { name: '确认时间' }).click()
  await page.getByRole('button', { name: '预约生效 v2' }).click()
  await page.getByRole('button', { name: '暂停预约' }).click()
  await expect(page.getByText('已暂停', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '恢复预约' }).click()
  const records = page.getByRole('table', { name: '定时发布记录', exact: true })
  await expect(records.getByText('已预约')).toBeVisible()
  await page.evaluate(() => window.scrollTo(0, 0))
  await page.screenshot({ path: info.outputPath('bundle-scheduled.png'), fullPage: true })
  await activate.click()
  await page.getByRole('button', { name: '确认整体生效', exact: true }).click()
  await expect(page.getByText('提交结果待确认')).toBeVisible()
  await page.screenshot({ path: info.outputPath('bundle-recover.png') })
  await page.getByRole('button', { name: '用原请求查询发布回执' }).click()
  await expect(page.getByText('当前生效版本 v2', { exact: true })).toBeVisible({ timeout: 15_000 })
  expect(keys).toHaveLength(2); expect(keys[1]).toBe(keys[0]); expect(input[1]).toEqual(input[0])
  await page.reload()
  await page.getByRole('spinbutton', { name: '组合版本号' }).fill('1')
  await page.getByRole('button', { name: '查看版本', exact: true }).click()
  await expect(page.getByRole('button', { name: '整体生效 v1' })).toBeDisabled()
  await page.getByRole('button', { name: '整体回滚至 v1' }).click()
  await page.getByRole('button', { name: '确认整体回滚' }).click()
  await expect(page.getByText('当前生效版本 v1', { exact: true })).toBeVisible()
  await expect(page.getByText('operation-9', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
})

test('late readiness cannot unlock a different version after a forbidden read', async ({ page }, info) => {
  const campaignId = campaignFixtures[0].id
  const now = new Date().toISOString()
  const members = (version: number) => ['decision', 'journey'].map(runtime => ({
    manifestId: `${runtime}-${version}`, environment: 'test', cell: 'cell-a', runtime, namespace: 'main', generation: version, expiresAt: new Date(Date.now() + 86400000).toISOString(), artifacts: [],
  }))
  const result = (version: number) => ({ bundleId: `b-${version}`, campaignId, version, environment: 'test', cell: 'cell-a', state: 'PREPARING',
    contentHash: `h-${version}`, members: members(version), createdBy: 'qa', createdAt: now })
  let releaseOld!: () => void, oldRequested!: () => void, writes = 0
  const hold = new Promise<void>(resolve => { releaseOld = resolve })
  const waiting = new Promise<void>(resolve => { oldRequested = resolve })
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    if (route.request().method() === 'POST' && path.includes('/release-bundles/')) writes++
    if (path.endsWith('/campaigns')) return route.fulfill({ json: campaignFixtures })
    if (path.endsWith('/latest')) return route.fulfill({ json: result(2) })
    if (path.endsWith('/versions/1')) return route.fulfill({ json: result(1) })
    if (path.endsWith('/versions/2/readiness')) {
      oldRequested(); await hold
      return route.fulfill({ json: { bundleId: 'b-2', version: 2, contentHash: 'h-2', runtimeReady: true, checkedAt: now, expiresAt: now,
        members: members(2).map(item => ({ ...item, readyReplicas: 1, readyCapacity: 2, ignoredEvidence: 0, runtimeReady: true })) } })
    }
    if (path.endsWith('/versions/1/readiness')) return route.fulfill({ status: 403, json: { status: 403, title: 'Forbidden', type: 'about:blank', detail: '当前身份无权读取历史就绪证据' } })
    if (path.endsWith('/operations')) return route.fulfill({ json: { latestOperation: null, appliedVersion: null, appliedSequence: 0, rollbackEligible: false } })
    if (path.endsWith('/approvals/page')) return route.fulfill({ json: { items: [], nextCursor: null, hasMore: false, status: url.searchParams.get('status') } })
    return route.fulfill({ json: [] })
  })
  await page.goto(`/releases/bundles?campaignId=${campaignId}`)
  await waiting
  await page.getByRole('spinbutton', { name: '组合版本号' }).fill('1')
  await page.getByRole('button', { name: '查看版本', exact: true }).click()
  await expect(page.getByText('当前身份无权读取历史就绪证据')).toBeVisible()
  const response = page.waitForResponse(resp => resp.url().endsWith('/versions/2/readiness'))
  releaseOld(); await response
  await expect(page.getByRole('button', { name: '整体生效 v1' })).toBeDisabled()
  await expect(page.getByText('成员已就绪', { exact: true })).toHaveCount(0)
  await expect(page.getByRole('table', { name: '组合成员', exact: true })).not.toContainText('decision-2')
  await page.screenshot({ path: info.outputPath('bundle-permission.png') })
  expect(writes).toBe(0)
})

test('first-version deep link opens an empty campaign for preparation', async ({ page }) => {
  const campaignId = campaignFixtures[0].id
  const missingVersions: string[] = []
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/campaigns')) return route.fulfill({ json: campaignFixtures })
    if (url.pathname.endsWith('/release-bundles/latest')) return route.fulfill({ status: 404, json: {
      type: 'about:blank', title: 'Not Found', code: 'RELEASE_BUNDLE_NOT_FOUND', status: 404, detail: '该活动尚无所请求的发布组合版本',
    } })
    if (url.pathname.includes('/versions/')) missingVersions.push(url.pathname)
    if (url.pathname.endsWith('/approvals/page')) return route.fulfill({ json: { items: [], nextCursor: null, hasMore: false, status: url.searchParams.get('status') } })
    return route.fulfill({ json: [] })
  })
  await page.goto(`/releases/bundles?campaignId=${campaignId}&version=1`)
  await expect(page.getByText('当前活动暂无组合版本', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '创建组合版本' }).click()
  await expect(page.getByRole('button', { name: '确认创建 v1' })).toBeDisabled()
  await expect(page.getByText('组合版本读取失败', { exact: true })).toHaveCount(0)
  expect(missingVersions).toEqual([])
})
