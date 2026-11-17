import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { GraphDefinition } from '../../shared/api/schemas'
import { devAuthState } from '../../shared/auth/auth-context'

const mocks = vi.hoisted(() => ({ latest: vi.fn(), registry: vi.fn(), save: vi.fn(), validate: vi.fn(), simulate: vi.fn(), submit: vi.fn(), benefits: vi.fn(), canWrite: true }))
vi.mock('../../shared/auth/useAuth', () => ({ useAuth: () => ({ ...devAuthState, hasPermission: (permission: string) => (permission !== 'definition:write' || mocks.canWrite) && devAuthState.hasPermission(permission) }) }))
vi.mock('../../shared/api/client', async (original) => {
  const actual = await original<typeof import('../../shared/api/client')>()
  return { ...actual, api: { ...actual.api, demoMode: false, latestDefinition: mocks.latest, nodeRegistry: mocks.registry,
    saveDefinition: mocks.save, validate: mocks.validate, simulate: mocks.simulate, submit: mocks.submit, benefits: mocks.benefits } }
})
vi.mock('@xyflow/react', async (original) => {
  const actual = await original<typeof import('@xyflow/react')>()
  return { ...actual, ReactFlow: ({ nodes, onNodeClick }: { nodes: { id: string; data: { label: string } }[]; onNodeClick: (event: unknown, node: unknown) => void }) => <div>{nodes.map((node) => <button key={node.id} onClick={() => onNodeClick(null, node)}>{`画布:${node.data.label}`}</button>)}</div> }
})
import { LowCodeDesigner } from './LowCodeDesigner'

const graph: GraphDefinition = { definitionId: 'stored-offer', dialect: 'OFFER_DECISION_DAG', dialectVersion: '1.0.0',
  nodes: [{ id: 'discount', stableTypeId: 'offer.fixed', semanticVersion: '1.0.0', config: { amountMinor: '500', validFrom: '2026-01-01T00:00:00Z', validTo: '2027-01-01T00:00:00Z', benefitDefinitionVersion: 'benefit@1' } }],
  edges: [], variables: {}, annotations: { terms: 'offer terms' } }
function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={client}><LowCodeDesigner title="测试 Offer" version="尚未保存" dialect="OFFER_DECISION_DAG" definitionId="fallback-offer" campaignId="campaign" nodes={[]} edges={[]} problems={[]} /></QueryClientProvider>)
}
async function load() {
  mount()
  fireEvent.click(await screen.findByRole('button', { name: '画布:定额优惠' }))
  await screen.findByLabelText('减免金额（分）')
}
async function validate() {
  fireEvent.click(screen.getByRole('button', { name: '校验' }))
  await waitFor(() => expect(screen.getByRole('button', { name: '提交审核' })).toBeEnabled())
}

describe('LowCodeDesigner real contract interactions', () => {
  beforeEach(() => {
    vi.clearAllMocks(); localStorage.clear(); mocks.canWrite = true
    mocks.latest.mockResolvedValue({ graph, version: 1 })
    mocks.registry.mockResolvedValue(['offer.start', 'offer.fixed', 'offer.percentage', 'offer.end', 'offer.condition'].map((stableTypeId) => ({ stableTypeId, semanticVersion: '1.0.0', dialects: ['OFFER_DECISION_DAG'], costWeight: 10 })))
    mocks.benefits.mockResolvedValue([{ benefitId: 'benefit', version: 1, name: '测试权益', status: 'ACTIVE' }])
    mocks.save.mockResolvedValue({ version: 2 })
    mocks.validate.mockResolvedValue({ valid: true, issues: [] })
    mocks.simulate.mockResolvedValue({ discountMinor: 500, payableMinor: 2000, subtotalMinor: 2500, trace: [] })
    mocks.submit.mockResolvedValue({ caseId: 'case-2' })
  })
  it('saves typed amount config without inventing an invisible terminal and filters supported palette', async () => {
    await load()
    expect(screen.queryByRole('button', { name: /offer.condition/ })).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('减免金额（分）'), { target: { value: '800' } })
    fireEvent.change(screen.getByRole('textbox', { name: '搜索节点' }), { target: { value: '比例' } })
    expect(screen.getByRole('button', { name: /比例优惠/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^定额优惠/ })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '保存' }))
    await waitFor(() => expect(mocks.save).toHaveBeenCalled())
    expect(mocks.save.mock.calls[0][0].graph.nodes).toEqual([{ ...graph.nodes[0], config: { ...graph.nodes[0].config, amountMinor: '800' } }])
  })
  it('invalidates approved revision after an edit and sends explicit amount/currency to preview', async () => {
    await load(); await validate()
    fireEvent.change(screen.getByLabelText('试算订单金额（分）'), { target: { value: '2500' } })
    fireEvent.change(screen.getByLabelText('试算币种'), { target: { value: 'usd' } })
    fireEvent.click(screen.getByRole('button', { name: '金额试算' }))
    await waitFor(() => expect(mocks.simulate).toHaveBeenCalledWith('stored-offer', 2, { amountMinor: '2500', currency: 'USD' }))
    await screen.findByText(/金额试算（USD）/)
    fireEvent.change(screen.getByLabelText('减免金额（分）'), { target: { value: '600' } })
    expect(screen.getByRole('button', { name: '提交审核' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '金额试算' })).toBeDisabled()
  })
  it('ignores a validation response for a revision edited while the request is in flight', async () => {
    let finish!: (value: { valid: boolean; issues: [] }) => void
    mocks.validate.mockImplementation(() => new Promise((resolve) => { finish = resolve }))
    await load()
    fireEvent.click(screen.getByRole('button', { name: '校验' }))
    await waitFor(() => expect(mocks.validate).toHaveBeenCalled())
    fireEvent.change(screen.getByLabelText('减免金额（分）'), { target: { value: '900' } })
    await act(async () => { finish({ valid: true, issues: [] }) })
    expect(screen.getByRole('button', { name: '提交审核' })).toBeDisabled()
    expect(screen.getByText('有未保存更改')).toBeInTheDocument()
    expect(screen.getByLabelText('减免金额（分）')).toHaveValue(900)
  })
  it('retains the saved version when validation fails and clears that error on a new save', async () => {
    mocks.validate.mockRejectedValue(new Error('校验服务不可用'))
    await load()
    fireEvent.click(screen.getByRole('button', { name: '校验' }))
    await screen.findByText(/v2 已保存，但校验失败/)
    expect(screen.getByRole('button', { name: '提交审核' })).toBeDisabled()
    expect(screen.getByText('已保存')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '保存' }))
    await screen.findByText('已写入版本 v2，校验后可提交审核')
    expect(screen.queryByText(/校验服务不可用/)).not.toBeInTheDocument()
    expect(screen.getByLabelText('减免金额（分）')).toHaveValue(500)
  })

  it('locates the backend nodeId and leaves graph-wide problems unassigned', async () => {
    mocks.validate.mockResolvedValue({ valid: false, issues: [
      { code: 'NODE', message: '修改此节点', pointer: '/nodes', nodeId: 'discount', severity: 'ERROR' },
      { code: 'GRAPH', message: '修复连接', pointer: '/nodes', severity: 'ERROR' },
    ] })
    await load()
    fireEvent.click(screen.getByRole('button', { name: '校验' }))
    await screen.findByText('修改此节点')
    const locate = screen.getAllByRole('button', { name: '查看节点' })
    expect(locate[0]).toBeEnabled(); expect(locate[1]).toBeDisabled()
    fireEvent.click(locate[0]); expect(screen.getByLabelText('减免金额（分）')).toHaveValue(500)
  })
})


describe('historical Journey draft migration', () => {
  const historical: GraphDefinition = {
    definitionId: 'frozen-journey', dialect: 'JOURNEY_STATE_MACHINE', dialectVersion: '1.0.0',
    nodes: [{ id: 'wait', stableTypeId: 'journey.wait_timer', semanticVersion: '1.0.0', config: { delaySeconds: '1800', retained: 'yes' } }],
    edges: [{ id: 'edge', sourceNodeId: 'wait', sourcePort: 'elapsed', targetNodeId: 'end', targetPort: 'in' }],
    variables: { stateTtlSeconds: '7200' }, annotations: { terms: '历史条款' },
  }
  function mountJourney() {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
    return render(<QueryClientProvider client={client}><LowCodeDesigner title="历史旅程" version="尚未保存" dialect="JOURNEY_STATE_MACHINE" definitionId="fallback-journey" campaignId="campaign" nodes={[]} edges={[]} problems={[]} /></QueryClientProvider>)
  }
  beforeEach(() => {
    vi.clearAllMocks(); localStorage.clear(); mocks.canWrite = true
    mocks.latest.mockResolvedValue({ graph: structuredClone(historical), version: 1 })
    mocks.registry.mockResolvedValue([{ stableTypeId: 'journey.wait-timer', semanticVersion: '1.0.0', dialects: ['JOURNEY_STATE_MACHINE'], costWeight: 10 }])
    mocks.save.mockResolvedValue({ version: 2 })
    mocks.validate.mockResolvedValue({ valid: true, issues: [] })
  })
  it('requires an explicit reversible edit and saves a new draft without changing the original graph', async () => {
    mountJourney()
    const migrate = await screen.findByRole('button', { name: '转换等待节点' })
    expect(migrate).toBeEnabled()
    expect(mocks.save).not.toHaveBeenCalled()
    fireEvent.click(migrate)
    expect(screen.getByText('有未保存更改')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '提交审核' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '旅程仿真' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '撤销' }))
    expect(screen.getByRole('button', { name: '转换等待节点' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: '重做' }))
    fireEvent.click(screen.getByRole('button', { name: '保存' }))
    await waitFor(() => expect(mocks.save).toHaveBeenCalledTimes(1))
    expect(mocks.save.mock.calls[0][0]).toEqual({ campaignId: 'campaign', graph: { ...historical, nodes: [{ ...historical.nodes[0], stableTypeId: 'journey.wait-timer' }] } })
    expect(historical.nodes[0].stableTypeId).toBe('journey.wait_timer')
    expect(mocks.submit).not.toHaveBeenCalled()
  })
  it('does not enable conversion when the target registry is unavailable', async () => {
    mocks.registry.mockRejectedValue(new Error('registry unavailable'))
    mountJourney()
    expect(await screen.findByRole('button', { name: '转换等待节点' })).toBeDisabled()
    expect(mocks.save).not.toHaveBeenCalled()
  })
  it('does not allow a read-only operator to convert or save', async () => {
    mocks.canWrite = false
    mountJourney()
    expect(await screen.findByRole('button', { name: '转换等待节点' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '保存' })).toBeDisabled()
    expect(mocks.save).not.toHaveBeenCalled()
  })
  it('leaves an unknown semantic version unchanged', async () => {
    mocks.latest.mockResolvedValue({ graph: { ...historical, nodes: [{ ...historical.nodes[0], semanticVersion: '2.0.0' }] }, version: 1 })
    mountJourney()
    await screen.findByText('已保存')
    expect(screen.queryByRole('button', { name: '转换等待节点' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '保存' }))
    await waitFor(() => expect(mocks.save).toHaveBeenCalled())
    expect(mocks.save.mock.calls[0][0].graph.nodes[0]).toEqual({ ...historical.nodes[0], semanticVersion: '2.0.0' })
  })
})
