import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthStateContext, devAuthState } from '../../shared/auth/auth-context'
import { DashboardPage } from './DashboardPage'

const mocks = vi.hoisted(() => ({ dashboard: vi.fn(), series: vi.fn(), campaigns: vi.fn() }))
vi.mock('../../shared/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../shared/api/client')>()
  return { ...actual, api: { ...actual.api, demoMode: false, ...mocks,
    releasePage: async () => ({ items: [], hasMore: false, nextCursor: null, state: null }) } }
})
function show() {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AuthStateContext.Provider value={devAuthState}><MemoryRouter><DashboardPage /></MemoryRouter></AuthStateContext.Provider></QueryClientProvider>)
}
const metric = (label: string) => within(screen.getByText(label).closest('article')!)

describe('dashboard evidence-based status', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mocks.dashboard.mockResolvedValue({ counts: {}, attributedRevenueMinor: 1, fundingCostMinor: 0, completeThrough: '2026-09-16T15:28:22Z', lag: { seconds: 5337477 } })
    mocks.campaigns.mockResolvedValue([{ id: 'draft', status: 'DRAFT' }, { id: 'running', status: 'ACTIVE' }])
    mocks.series.mockResolvedValue({ points: [{ at: '2026-11-17T01:00:00Z', revenueMinor: 0 }, { at: '2026-11-17T02:00:00Z', revenueMinor: 100 }] })
  })
  it('labels loaded campaigns and old watermark truthfully while preserving cents and a zero bar', async () => {
    show()
    await screen.findByText('61 天 18 小时')
    expect(screen.getByRole('heading', { name: '查看当前运营数据' })).toBeInTheDocument()
    expect(screen.queryByText(/增长脉搏稳定|在线活动|实时经营/)).not.toBeInTheDocument()
    expect(metric('已加载活动').getByText('2')).toBeInTheDocument()
    expect(metric('今日归因成交').getByText('¥0.01')).toBeInTheDocument()
    expect(screen.getByText(/2026\/09\/16 23:28:22/)).toBeInTheDocument()
    const bars = screen.getByLabelText('今日小时归因收入').querySelectorAll('span')
    expect(bars[0].style.getPropertyValue('--height')).toBe('0%')
    expect(bars[1].style.getPropertyValue('--height')).toBe('100%')
  })
  it('does not turn missing lag or a failed campaign query into zero', async () => {
    mocks.dashboard.mockResolvedValue({ counts: {}, attributedRevenueMinor: 0, fundingCostMinor: 0 })
    mocks.campaigns.mockRejectedValue(new Error('campaign unavailable'))
    show()
    await screen.findByText('活动加载失败')
    expect(metric('水位延迟').getByText('未知')).toBeInTheDocument()
    expect(metric('已加载活动').getByText('—')).toBeInTheDocument()
  })
  it('renders signed hourly amounts as a table when the simple positive bar chart cannot represent them', async () => {
    mocks.series.mockResolvedValue({ points: [{ at: '2026-11-17T01:00:00Z', revenueMinor: -50 }, { at: '2026-11-17T02:00:00Z', revenueMinor: 100 }] })
    show()
    const table = await screen.findByRole('table', { name: '小时归因收入明细' })
    expect(within(table).getByText('-¥0.50')).toBeInTheDocument()
    expect(within(table).getByText('¥1.00')).toBeInTheDocument()
    expect(screen.queryByLabelText('今日小时归因收入')).not.toBeInTheDocument()
  })
})
