import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const { reconciliation } = vi.hoisted(() => ({ reconciliation: vi.fn() }))

vi.mock('../../shared/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../shared/api/client')>()
  return { ...actual, api: { ...actual.api, demoMode: false, reconciliation } }
})

import { ReconciliationPanel } from './OperationsPage'

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
    expect(screen.getByText('2026-09-06T08:00:00Z')).toBeInTheDocument()
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
