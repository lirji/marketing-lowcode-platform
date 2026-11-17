import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthStateContext, devAuthState } from '../../shared/auth/auth-context'
import { AppShell } from '../../app/AppShell'
import { DashboardPage } from '../dashboard/DashboardPage'

const mocks = vi.hoisted(() => ({ releasePage: vi.fn() }))
vi.mock('../../shared/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../shared/api/client')>()
  return { ...actual, api: { ...actual.api, demoMode: false, releasePage: mocks.releasePage,
    approvalPage: async () => ({ items: [], nextCursor: null, hasMore: false, status: 'OPEN' }),
    campaigns: async () => [], dashboard: async () => null, series: async () => ({ points: [] }) } }
})
const items = Array.from({ length: 25 }, (_, i) => ({ manifest: { manifestId: `manifest-${i}`, cell: 'cell-a', generation: i + 1, artifacts: [] }, state: 'ACTIVE', readyReplicas: 1, readyCapacity: 1 }))
function context(child: React.ReactNode, auth = devAuthState, client = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
  return <QueryClientProvider client={client}><AuthStateContext.Provider value={auth}><MemoryRouter>{child}</MemoryRouter></AuthStateContext.Provider></QueryClientProvider>
}
describe('release overview consumers', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mocks.releasePage.mockImplementation(({ state }) => Promise.resolve({ items, hasMore: true, nextCursor: 'manifest-24', state: state ?? null }))
  })
  it('labels a bounded ACTIVE count without claiming a number of release slots', async () => {
    render(context(<AppShell />))
    await screen.findByText('已激活清单 25+ 条')
    expect(mocks.releasePage).toHaveBeenCalledWith({ state: 'ACTIVE', limit: 25, cursor: undefined })
    expect(screen.queryByText(/Active 发布槽/)).not.toBeInTheDocument()
  })
  it('labels dashboard records as loaded and points to complete traversal', async () => {
    render(context(<DashboardPage />))
    await screen.findByText(/已加载 25 条发布清单，还有更多可在发布中心查看/)
    expect(mocks.releasePage).toHaveBeenCalledTimes(1)
  })
  it('hides cached counts and skips release requests after read permission is removed', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const view = render(context(<AppShell />, devAuthState, client))
    await screen.findByText('已激活清单 25+ 条')
    view.rerender(context(<AppShell />, { ...devAuthState, permissions: ['campaign:read'], hasPermission: (p) => p === 'campaign:read' }, client))
    await screen.findByText('无发布读取权限')
    await waitFor(() => expect(screen.queryByText('已激活清单 25+ 条')).not.toBeInTheDocument())
    expect(mocks.releasePage).toHaveBeenCalledTimes(1)
  })
})
