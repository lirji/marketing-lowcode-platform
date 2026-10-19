import { expect, test, type Locator } from '@playwright/test'

// 在真实布局中验证字段归属，避免同一行包含文案就误判“已分列”。
async function field(table: Locator, key: string, heading: string) {
  const headers = await table.getByRole('columnheader').allTextContents()
  const index = headers.indexOf(heading)
  expect(index, `missing column: ${heading}`).toBeGreaterThanOrEqual(0)
  return table.getByRole('row').filter({ has: table.page().getByRole('cell', { name: key, exact: true }) }).getByRole('cell').nth(index)
}

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true }
  })
  await page.route('**/api/v1/**', (route) => route.fulfill({ json: [] }))
})

test('operations keep identifiers, nullable metadata and guarded replay distinct', async ({ page }, testInfo) => {
  const enrollmentId = 'enrollment-very-long-stable-reference-123456789'
  await page.route('**/api/v1/enrollments?*', (route) => route.fulfill({ json: [{ enrollmentId, journeyId: 'journey-one', journeyVersion: 7, subjectToken: 'private-subject-never-show-complete', currentNodeId: 'node-wait', status: 'WAITING', duplicate: false }] }))
  await page.route('**/api/v1/contacts?*', (route) => route.fulfill({ json: [{ contactId: 'contact-1', contactKey: 'key-1', providerCode: 'OK', providerRequestId: 'provider-request-1', state: 'DELIVERED', updatedAt: '2026-10-19T00:00:00Z' }] }))
  await page.route('**/api/v1/quarantine', (route) => route.fulfill({ json: [{ quarantineId: 'quarantine-1', receiptId: 'receipt-1', reasonCode: 'VERSION_GAP', state: 'QUARANTINED', createdAt: '2026-10-19T00:00:00Z', replayedAt: null }] }))
  const writes: string[] = []
  page.on('request', (request) => { if (request.method() !== 'GET' && request.url().includes('/api/v1/')) writes.push(request.url()) })
  await page.goto('/operations')
  await page.getByRole('tab', { name: 'Journey 实例' }).click()
  const instances = page.getByRole('table', { name: 'Journey 实例' })
  await expect(instances.getByRole('cell', { name: enrollmentId, exact: true })).toBeVisible()
  await expect(await field(instances, enrollmentId, '旅程 ID')).toHaveText('journey-one')
  await expect(await field(instances, enrollmentId, '是否重复')).toHaveText('否')
  await expect(await field(instances, enrollmentId, '投影时间（北京时间）')).toHaveText('—')
  await expect(instances).not.toContainText('private-subject-never-show-complete')
  await expect(await field(instances, enrollmentId, '主体标识（脱敏）')).toHaveText('privat…')
  expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(1)
  await page.screenshot({ path: testInfo.outputPath('journey-fields.png') })
  await page.getByRole('tab', { name: '触达记录' }).click()
  const contacts = page.getByRole('table', { name: '触达记录' })
  await expect(contacts.getByRole('cell', { name: 'contact-1', exact: true })).toBeVisible()
  await expect(await field(contacts, 'contact-1', '服务商响应码')).toHaveText('OK')
  await expect(await field(contacts, 'contact-1', '服务商请求 ID')).toHaveText('provider-request-1')
  await expect(await field(contacts, 'contact-1', '更新时间（北京时间）')).toHaveText('2026/10/19 08:00:00')
  await page.getByRole('tab', { name: 'DLQ / 隔离区' }).click()
  const quarantine = page.getByRole('table', { name: '隔离消息' })
  await expect(quarantine.getByRole('cell', { name: 'quarantine-1', exact: true })).toBeVisible()
  await expect(await field(quarantine, 'quarantine-1', '回执 ID')).toHaveText('receipt-1')
  await expect(await field(quarantine, 'quarantine-1', '重放时间（北京时间）')).toHaveText('—')
  await quarantine.getByRole('button', { name: '重放', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('quarantine-1')
  await page.getByRole('button', { name: '取消', exact: true }).click()
  expect(writes).toEqual([])
})

test('approval rows retain the right action after horizontal keyboard scrolling', async ({ page }, testInfo) => {
  await page.route('**/api/v1/approvals', (route) => route.fulfill({ json: [
    { caseId: 'case-a', definitionId: 'definition-a', definitionVersion: 2, status: 'OPEN', submittedBy: 'actor-a', requiredRoles: ['BUSINESS', 'FINANCE'], approvals: { BUSINESS: 'reviewer-a' }, updatedAt: '2026-10-19T00:00:00Z' },
    { caseId: 'case-b', definitionId: 'definition-b', definitionVersion: 5, status: 'OPEN', submittedBy: 'actor-b', requiredRoles: ['BUSINESS'], approvals: {}, updatedAt: '2026-10-19T01:00:00Z' },
  ] }))
  await page.goto('/governance')
  const queue = page.getByRole('table', { name: '待审核变更' })
  await expect(queue.getByRole('cell', { name: 'case-b', exact: true })).toBeVisible()
  await expect(await field(queue, 'case-b', '定义 ID')).toHaveText('definition-b')
  await expect(await field(queue, 'case-b', '定义版本')).toHaveText('5')
  await expect(await field(queue, 'case-a', '已批准人数')).toHaveText('1')
  const scroll = page.getByRole('region', { name: '待审核变更表格' })
  await scroll.focus()
  await scroll.press('End')
  const action = queue.getByRole('button', { name: '查看审核：case-b' })
  const box = await action.boundingBox()
  expect(box!.x).toBeGreaterThanOrEqual(0)
  expect(box!.x + box!.width).toBeLessThanOrEqual(page.viewportSize()!.width)
  expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(1)
  await page.screenshot({ path: testInfo.outputPath('approval-fields.png') })
  await action.click()
  await expect(page.getByRole('dialog')).toContainText('definition-b')
  await expect(page.getByRole('dialog')).not.toContainText('definition-a')
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await expect(action).toBeFocused()
})
