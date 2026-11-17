import { expect, test } from '@playwright/test'

test('saved Journey simulation uses explicit input and displays waiting and proposed effects', async ({ page }, info) => {
  const campaignId = 'journey-simulation-campaign'
  const graph = { definitionId: 'journey-simulation-definition', dialect: 'JOURNEY_STATE_MACHINE', dialectVersion: '1.0.0',
    nodes: [{ id: 'trigger', stableTypeId: 'journey.trigger', semanticVersion: '1.0.0', config: {} },
      { id: 'wait', stableTypeId: 'journey.wait-event', semanticVersion: '1.0.0', config: { eventType: 'PAID' } },
      { id: 'send', stableTypeId: 'journey.send', semanticVersion: '1.0.0', config: { template: 'receipt' } },
      { id: 'end', stableTypeId: 'journey.end', semanticVersion: '1.0.0', config: {} }],
    edges: [{ id: 'a', sourceNodeId: 'trigger', sourcePort: 'next', targetNodeId: 'wait', targetPort: 'in' },
      { id: 'b', sourceNodeId: 'wait', sourcePort: 'matched', targetNodeId: 'send', targetPort: 'in' },
      { id: 'c', sourceNodeId: 'send', sourcePort: 'next', targetNodeId: 'end', targetPort: 'in' }], variables: {}, annotations: {} }
  await page.addInitScript(() => { window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'DEV', ALLOW_DEV_AUTH: true } })
  await page.route('**/api/v1/**', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/client-error', (route) => route.fulfill({ status: 204 }))
  await page.route('**/api/v1/campaigns', (route) => route.fulfill({ json: [{ id: campaignId, name: 'Journey 仿真验收', objective: '等事件后发送', status: 'DRAFT', campaignType: 'STANDARD', createdAt: '2026-11-16T00:00:00Z' }] }))
  await page.route('**/api/v1/registries/nodes', (route) => route.fulfill({ json: graph.nodes.map((node) => ({ stableTypeId: node.stableTypeId, semanticVersion: '1.0.0', dialects: ['JOURNEY_STATE_MACHINE'], costWeight: 10 })) }))
  await page.route('**/api/v1/definitions/latest?*', (route) => route.fulfill({ json: { graph, version: 1, definitionId: graph.definitionId, campaignId, dialect: graph.dialect, status: 'DRAFT' } }))
  await page.route('**/api/v1/definitions', (route) => route.fulfill({ status: 201, json: { definitionId: graph.definitionId, campaignId, version: 2, dialect: graph.dialect, status: 'DRAFT' } }))
  await page.route('**/versions/2:validate', (route) => route.fulfill({ json: { valid: true, issues: [] } }))
  let requests = 0
  await page.route('**/versions/2:simulate-journey', async (route) => {
    requests++
    const request = route.request().postDataJSON()
    expect(request.initialVariables).toEqual({ vip: 'yes' })
    const completed = request.events.length > 0
    if (completed) expect(request.events).toEqual([{ offsetSeconds: 10, eventType: 'PAID', attributes: {} }])
    await route.fulfill({ json: { kind: 'JOURNEY_SIMULATION', definitionId: graph.definitionId, version: 2, stopReason: completed ? 'COMPLETED' : 'WAITING', status: completed ? 'COMPLETED' : 'WAITING', currentNodeId: completed ? 'end' : 'wait', elapsedSeconds: completed ? 10 : 0, remainingEvents: 0, failureCode: '', pendingTimers: [],
      steps: [{ sequence: 1, signal: 'START', eventType: '', offsetSeconds: 0, fromNodeId: 'trigger', toNodeId: 'wait', status: 'WAITING', commands: [] }, ...(completed ? [{ sequence: 2, signal: 'EVENT', eventType: 'PAID', offsetSeconds: 10, fromNodeId: 'wait', toNodeId: 'end', status: 'COMPLETED', commands: [{ type: 'SEND', nodeId: 'send', commandId: 'preview-only-command' }] }] : [])] } })
  })
  await page.goto(`/designers/journey?campaignId=${campaignId}`)
  await expect(page.getByRole('button', { name: '旅程仿真' })).toBeDisabled()
  await page.getByRole('button', { name: '校验', exact: true }).click()
  await expect(page.getByRole('button', { name: '旅程仿真' })).toBeEnabled()
  await page.getByRole('button', { name: '旅程仿真' }).click()
  await page.getByRole('button', { name: '添加初始变量' }).click()
  await page.getByRole('textbox', { name: '初始变量 1 属性名' }).pressSequentially('vip')
  await page.getByRole('textbox', { name: '初始变量 1 属性值' }).pressSequentially('yes')
  await page.getByRole('button', { name: '运行仿真' }).click()
  await expect(page.getByText('模拟仍在等待', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '添加事件', exact: true }).click()
  await expect(page.getByRole('region', { name: 'Journey 仿真结果' })).toHaveCount(0)
  await page.getByRole('textbox', { name: '事件 1 类型' }).pressSequentially('PAID')
  await page.getByRole('spinbutton', { name: '事件 1 发生时间（秒）' }).fill('10')
  await page.screenshot({ path: info.outputPath('journey-input.png'), fullPage: true })
  await page.getByRole('button', { name: '运行仿真' }).click()
  await expect(page.getByText('模拟已到达结束节点', { exact: true })).toBeVisible()
  await page.getByRole('table', { name: '拟生成指令', exact: true }).scrollIntoViewIfNeeded()
  await expect(page.getByRole('table', { name: '拟生成指令', exact: true })).toHaveText(/发送消息/)
  await page.screenshot({ path: info.outputPath('journey-result.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
  await page.getByRole('button', { name: '关闭', exact: true }).click()
  expect(requests).toBe(2)
})
