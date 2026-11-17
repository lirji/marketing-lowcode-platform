import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthStateContext, devAuthState } from '../../shared/auth/auth-context'
import { DashboardPage } from '../dashboard/DashboardPage'
import { OperationsPage } from './OperationsPage'

const reads = vi.hoisted(() => ({
  dashboard: vi.fn(), series: vi.fn(), campaigns: vi.fn(), releasePage: vi.fn(),
  traceByRequest: vi.fn(), traceByOrder: vi.fn(), enrollments: vi.fn(), contacts: vi.fn(),
  quarantine: vi.fn(), reconciliation: vi.fn(), awardIntents: vi.fn(), replayQuarantine: vi.fn(),
}))
vi.mock('../../shared/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../shared/api/client')>()
  return { ...actual, api: { ...actual.api, demoMode: true, ...reads } }
})

function show(page: 'dashboard' | 'operations') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  // 已停用的查询仍可能持有旧数据；预览必须连缓存中的旧业务结果也不展示。
  client.setQueryData(['dashboard'], { attributedRevenueMinor: 987654321, fundingCostMinor: 12345, lag: { seconds: 0 } })
  client.setQueryData(['series', 'hour', 'today'], { points: [{ at: '2026-11-17T01:00:00Z', revenueMinor: 998877 }] })
  client.setQueryData(['campaigns'], [{ id: 'private-campaign', name: '旧缓存活动' }])
  client.setQueryData(['enrollments'], [{ enrollmentId: 'private-enrollment', state: 'ACTIVE' }])
  client.setQueryData(['contacts'], [{ contactId: 'private-contact', state: 'DELIVERED' }])
  client.setQueryData(['quarantine'], [{ quarantineId: 'private-message', reasonCode: 'PRIVATE_REASON' }])
  client.setQueryData(['reconciliation'], { balanced: true, violations: [], ledgerMovement: 999 })
  client.setQueryData(['award-intents', 'private-campaign'], { pages: [[{ intentId: 'private-award', campaignId: 'private-campaign', subjectHash: 'private-subject-hash', sourceRequestId: 'private-request', sourceSystem: 'JOURNEY', definitionVersion: 1, deliveryMode: 'CENTER', status: 'SENT', attempts: 1 }]], pageParams: [undefined] })
  render(<QueryClientProvider client={client}><AuthStateContext.Provider value={devAuthState}><MemoryRouter initialEntries={['/operations?campaignId=private-campaign']}>{page === 'dashboard' ? <DashboardPage /> : <OperationsPage />}</MemoryRouter></AuthStateContext.Provider></QueryClientProvider>)
}

function expectNoApiCalls() {
  for (const method of Object.values(reads)) expect(method).not.toHaveBeenCalled()
}

describe('static preview has no business evidence', () => {
  beforeEach(() => vi.clearAllMocks())

  it('leaves dashboard metrics unknown and hides cached charts and release claims', () => {
    show('dashboard')
    const metrics = screen.getByRole('region', { name: '核心指标' })
    expect(within(metrics).getAllByText('—')).toHaveLength(4)
    expect(screen.getByText('收入序列需要连接服务')).toBeInTheDocument()
    expect(screen.getByText('发布状态需要连接服务')).toBeInTheDocument()
    expect(screen.queryByLabelText('今日小时归因收入')).not.toBeInTheDocument()
    expect(screen.queryByRole('table', { name: 'Cell 就绪度' })).not.toBeInTheDocument()
    expect(screen.queryByText(/9,876,543|998,877|旧缓存活动|1842/)).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: '查看活动入口' })).toHaveAttribute('href', '/campaigns')
    expectNoApiCalls()
  })

  it('keeps every operations tab read-free and never reports fake success or cached records', async () => {
    show('operations')
    const cases = [
      ['全链路查询', '营销轨迹需要连接服务'], ['Journey 实例', 'Journey 实例需要连接服务'],
      ['触达记录', '触达记录需要连接服务'], ['发放', '演示模式不查询真实发放指令'],
      ['DLQ / 隔离区', '隔离消息需要连接服务'], ['资金对账', '资金对账需要连接服务'],
    ]
    for (const [tab, notice] of cases) {
      await userEvent.click(screen.getByRole('tab', { name: tab }))
      expect(screen.getByText(notice)).toBeInTheDocument()
      expect(screen.queryByRole('table')).not.toBeInTheDocument()
      expect(screen.queryByText(/private-|PRIVATE_REASON|本货主资金守恒成立|未发现差异|CATEGORY_MATCH|^PASS$/)).not.toBeInTheDocument()
      expectNoApiCalls()
    }
    expect(screen.queryByRole('button', { name: '查询' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '确认重放' })).not.toBeInTheDocument()
  })
})
