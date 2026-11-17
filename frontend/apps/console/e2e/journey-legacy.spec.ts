import { expect, test } from '@playwright/test'

test('historical timer conversion preserves the graph and creates a separate validated draft', async ({ page }, info) => {
  const campaignId = 'legacy-timer-campaign'
  const graph = { definitionId: 'legacy-timer-definition', dialect: 'JOURNEY_STATE_MACHINE', dialectVersion: '1.0.0',
    nodes: [{ id: 'start', stableTypeId: 'journey.trigger', semanticVersion: '1.0.0', config: {} },
      { id: 'wait', stableTypeId: 'journey.wait_timer', semanticVersion: '1.0.0', config: { delaySeconds: '1800' } },
      { id: 'end', stableTypeId: 'journey.end', semanticVersion: '1.0.0', config: {} }],
    edges: [{ id: 'start-wait', sourceNodeId: 'start', sourcePort: 'next', targetNodeId: 'wait', targetPort: 'in' },
      { id: 'wait-end', sourceNodeId: 'wait', sourcePort: 'elapsed', targetNodeId: 'end', targetPort: 'in' }],
    variables: { stateTtlSeconds: '7200' }, annotations: { terms: '保留历史条款' } }
  const expectedGraph = { ...graph, nodes: graph.nodes.map(node => node.id === 'wait' ? { ...node, stableTypeId: 'journey.wait-timer' } : node) }
  const writes: string[] = [], errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    if (route.request().method() !== 'GET') writes.push(path)
    if (path.endsWith('/campaigns')) { await route.fulfill({ json: [{ id: campaignId, name: '历史旅程', objective: '保留原版本', status: 'ACTIVE', campaignType: 'STANDARD', createdAt: '2026-09-16T00:00:00Z' }] }); return }
    if (path.endsWith('/registries/nodes')) { await route.fulfill({ json: expectedGraph.nodes.map(node => ({ stableTypeId: node.stableTypeId, semanticVersion: node.semanticVersion, dialects: [graph.dialect], costWeight: 10 })) }); return }
    if (path.endsWith('/definitions/latest')) { await route.fulfill({ json: { graph, version: 1, definitionId: graph.definitionId, campaignId, dialect: graph.dialect, status: 'APPROVED' } }); return }
    if (path.endsWith('/definitions')) {
      expect(route.request().postDataJSON()).toEqual({ campaignId, graph: expectedGraph })
      await route.fulfill({ status: 201, json: { definitionId: graph.definitionId, campaignId, version: 2, dialect: graph.dialect, status: 'DRAFT' } }); return
    }
    if (path.endsWith('/versions/2:validate')) { await route.fulfill({ json: { valid: true, issues: [] } }); return }
    if (path.endsWith('/versions/2:simulate-journey')) {
      expect(route.request().postDataJSON().horizonSeconds).toBe(3600)
      await route.fulfill({ json: { kind: 'JOURNEY_SIMULATION', definitionId: graph.definitionId, version: 2,
        stopReason: 'COMPLETED', status: 'COMPLETED', currentNodeId: 'end', elapsedSeconds: 1800, remainingEvents: 0,
        failureCode: '', pendingTimers: [], steps: [{ sequence: 1, signal: 'TIMER', eventType: '', offsetSeconds: 1800, fromNodeId: 'wait', toNodeId: 'end', status: 'COMPLETED', commands: [] }] } }); return
    }
    if (path.endsWith('/releases/page')) { await route.fulfill({ json: { items: [], nextCursor: null, hasMore: false, state: url.searchParams.get('state') } }); return }
    if (path.endsWith('/approvals/page')) { await route.fulfill({ json: { items: [], nextCursor: null, hasMore: false, status: url.searchParams.get('status') } }); return }
    expect(route.request().method()).toBe('GET')
    await route.fulfill({ json: [] })
  })
  await page.goto(`/designers/journey?campaignId=${campaignId}`)
  await expect(page.getByRole('button', { name: '转换等待节点' })).toBeEnabled()
  expect(writes).toEqual([])
  await page.screenshot({ path: info.outputPath('legacy-notice.png'), fullPage: true })
  await page.getByRole('button', { name: '转换等待节点' }).click()
  await expect(page.getByText('有未保存更改', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '旅程仿真' })).toBeDisabled()
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  await expect(page.getByRole('button', { name: '转换等待节点' })).toBeEnabled()
  await page.getByRole('button', { name: '重做', exact: true }).click()
  await page.getByRole('button', { name: '校验', exact: true }).click()
  await expect(page.getByRole('button', { name: '旅程仿真' })).toBeEnabled()
  await page.getByRole('button', { name: '旅程仿真' }).click()
  await page.getByRole('button', { name: '运行仿真' }).click()
  await expect(page.getByText('模拟已到达结束节点', { exact: true })).toBeVisible()
  await page.screenshot({ path: info.outputPath('migrated-simulation.png'), fullPage: true })
  expect(writes).toEqual(['/api/v1/definitions', `/api/v1/definitions/${graph.definitionId}/versions/2:validate`, `/api/v1/definitions/${graph.definitionId}/versions/2:simulate-journey`])
  expect(graph.nodes[1].stableTypeId).toBe('journey.wait_timer')
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
  expect(errors).toEqual([])
})
