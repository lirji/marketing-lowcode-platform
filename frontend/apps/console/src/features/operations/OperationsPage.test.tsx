import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const { reconciliation, traceByRequest, traceByOrder } = vi.hoisted(() => ({ reconciliation: vi.fn(), traceByRequest: vi.fn(), traceByOrder: vi.fn() }))

vi.mock('../../shared/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../shared/api/client')>()
  return { ...actual, api: { ...actual.api, demoMode: false, reconciliation, traceByRequest, traceByOrder } }
})

import userEvent from '@testing-library/user-event'

import { ReconciliationPanel, TraceSearch } from './OperationsPage'

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><ReconciliationPanel /></QueryClientProvider>)
}

describe('ReconciliationPanel', () => {
  beforeEach(() => reconciliation.mockReset())

  it('renders every live violation and the report metadata', async () => {
    reconciliation.mockResolvedValue({
      balanced: false,
      violations: ['RESERVED_TOTAL_MISMATCH:resource-a', 'CONSUMED_TOTAL_MISMATCH:resource-b'],
      ledgerMovement: 42,
      checkedAt: '2026-09-06T08:00:00Z',
    })
    renderPanel()
    const list = await screen.findByLabelText('全部对账差异')
    expect(list).toHaveTextContent('RESERVED_TOTAL_MISMATCH:resource-a')
    expect(list).toHaveTextContent('CONSUMED_TOTAL_MISMATCH:resource-b')
    expect(screen.getByText('42')).toBeInTheDocument()
    expect(screen.getByText('2026/09/06 16:00:00')).toHaveAttribute('datetime', '2026-09-06T08:00:00Z')
    expect(screen.getByText('核对时间（北京时间）')).toBeInTheDocument()
    expect(screen.queryByText('2026-09-06T08:00:00Z')).not.toBeInTheDocument()
    expect(screen.getByText(/对账只报告差异，不执行补发/)).toBeInTheDocument()
  })

  it('uses the live empty result instead of demo numbers', async () => {
    reconciliation.mockResolvedValue({ balanced: true, violations: [], ledgerMovement: 0, checkedAt: '2026-09-06T08:00:00Z' })
    renderPanel()
    expect(await screen.findByText('本货主资金守恒成立')).toBeInTheDocument()
    expect(screen.getByText('未发现差异')).toBeInTheDocument()
    expect(screen.queryByText('不变量差异')).not.toBeInTheDocument()
  })
})


describe('live trace search', () => {
  beforeEach(() => { traceByRequest.mockReset(); traceByOrder.mockReset() })

  function renderTrace() {
    const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
    render(<QueryClientProvider client={client}><TraceSearch /></QueryClientProvider>)
  }

  async function search(id: string) {
    await userEvent.clear(screen.getByPlaceholderText('填写完整请求 ID'))
    await userEvent.type(screen.getByPlaceholderText('填写完整请求 ID'), id)
    await userEvent.click(screen.getByRole('button', { name: '查询' }))
  }

  it('shows pending and failure without fabricated decision counts', async () => {
    let reject!: (error: Error) => void
    traceByRequest.mockImplementation(() => new Promise((_, fail) => { reject = fail }))
    renderTrace()
    await search('request-failure')
    expect(await screen.findByText('正在查询轨迹')).toBeInTheDocument()
    expect(screen.queryByText('候选与裁决')).not.toBeInTheDocument()
    await act(async () => reject(new Error('unavailable')))
    expect(await screen.findByText('轨迹查询失败')).toBeInTheDocument()
    expect(screen.queryByText('CATEGORY_MATCH')).not.toBeInTheDocument()
    expect(screen.queryByText('演示')).not.toBeInTheDocument()
  })

  it('shows an explicit empty result and does not search whitespace', async () => {
    traceByRequest.mockResolvedValue(undefined)
    renderTrace()
    await userEvent.type(screen.getByPlaceholderText('填写完整请求 ID'), '  {Enter}')
    expect(traceByRequest).not.toHaveBeenCalled()
    await search('empty-request')
    expect(await screen.findByText('未找到轨迹')).toBeInTheDocument()
    expect(screen.queryByText('候选与裁决')).not.toBeInTheDocument()
  })

  it('switches lookup kind without accepting a late request lookup', async () => {
    let resolve!: (value: unknown) => void
    traceByRequest.mockImplementation(() => new Promise((done) => { resolve = done }))
    traceByOrder.mockResolvedValue({ requestId: 'order-request', maskedSubject: 'order-subject', events: [] })
    renderTrace()
    await search('shared-id')
    await userEvent.click(screen.getByRole('combobox'))
    await userEvent.click(screen.getByRole('option', { name: '订单 ID（Order ID）' }))
    await userEvent.click(screen.getByRole('button', { name: '查询' }))
    expect(await screen.findByText('order-subject')).toBeInTheDocument()
    expect(traceByOrder).toHaveBeenCalledWith('shared-id')
    await act(async () => resolve({ requestId: 'old-request', maskedSubject: 'old-subject' }))
    expect(screen.queryByText('old-subject')).not.toBeInTheDocument()
    expect(screen.getByText('order-subject')).toBeInTheDocument()
  })

  it.each(['success', 'error'])('keeps the latest result when an older query finishes with %s', async (outcome) => {
    let resolve!: (value: unknown) => void
    let reject!: (reason: Error) => void
    traceByRequest.mockImplementationOnce(() => new Promise((done, fail) => { resolve = done; reject = fail }))
      .mockResolvedValueOnce({ requestId: 'request-new', maskedSubject: 'new-subject', events: [], candidates: { chosen: 'new' } })
    renderTrace()
    await search('request-old')
    await search('request-new')
    expect(await screen.findByText('new-subject')).toBeInTheDocument()
    await act(async () => {
      if (outcome === 'success') resolve({ requestId: 'request-old', maskedSubject: 'old-subject' })
      else reject(new Error('late failure'))
    })
    expect(screen.getByText('new-subject')).toBeInTheDocument()
    expect(screen.queryByText('old-subject')).not.toBeInTheDocument()
    expect(screen.queryByText('轨迹查询失败')).not.toBeInTheDocument()
    await userEvent.clear(screen.getByPlaceholderText('填写完整请求 ID'))
    await userEvent.type(screen.getByPlaceholderText('填写完整请求 ID'), 'unsubmitted-id')
    expect(screen.getByText('request-new', { selector: 'code' })).toBeInTheDocument()
    expect(screen.queryByText('unsubmitted-id', { selector: 'code' })).not.toBeInTheDocument()
  })
})
