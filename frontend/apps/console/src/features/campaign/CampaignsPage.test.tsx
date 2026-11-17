import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthStateContext, devAuthState, type AuthState } from '../../shared/auth/auth-context'
import { campaignFixtures } from '../../test/fixtures/campaigns'

const mocks = { demo: false, campaigns: vi.fn(), create: vi.fn() }
vi.mock('../../shared/api/client', async original => {
  const actual = await original<typeof import('../../shared/api/client')>()
  return { ...actual, api: { ...actual.api, get demoMode() { return mocks.demo }, campaigns: () => mocks.campaigns(), createCampaign: (payload: unknown) => mocks.create(payload) } }
})
import { CampaignsPage } from './CampaignsPage'

function Location() { const location = useLocation(); return <output aria-label="当前位置">{location.pathname + location.search}</output> }
function mount(path = '/campaigns', auth: AuthState = devAuthState, client = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
  const view = (identity: AuthState) => <QueryClientProvider client={client}><AuthStateContext.Provider value={identity}><MemoryRouter initialEntries={[path]}><CampaignsPage /><Location /></MemoryRouter></AuthStateContext.Provider></QueryClientProvider>
  const result = render(view(auth)); return { ...result, updateAuth: (next: AuthState) => result.rerender(view(next)) }
}

describe('campaign records come from the service', () => {
  beforeEach(() => { mocks.demo = false; mocks.campaigns.mockReset().mockResolvedValue([]); mocks.create.mockReset() })

  it('does not show cached or fixture campaigns or enable fake creation in static preview', () => {
    mocks.demo = true
    const client = new QueryClient()
    client.setQueryData(['campaigns', devAuthState.tenantId, devAuthState.subject, devAuthState.organizations, devAuthState.shops, devAuthState.permissions], campaignFixtures)
    mount('/campaigns?create=true', devAuthState, client)
    expect(screen.getByText('活动列表需要连接后端')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^创建活动$/ })).toBeDisabled()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /刷新/ })).not.toBeInTheDocument()
    expect(screen.queryByText(campaignFixtures[0].name)).not.toBeInTheDocument()
    expect(mocks.campaigns).not.toHaveBeenCalled(); expect(mocks.create).not.toHaveBeenCalled()
  })

  it('renders only API records and replaces them with the real empty result on refresh', async () => {
    mocks.campaigns.mockResolvedValueOnce([campaignFixtures[0]]).mockResolvedValue([])
    mount()
    expect(await screen.findByText(campaignFixtures[0].name)).toBeInTheDocument()
    expect(screen.queryByText(campaignFixtures[1].name)).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /刷新/ }))
    expect(await screen.findByText('还没有营销活动')).toBeInTheDocument()
    expect(screen.queryByText(campaignFixtures[0].name)).not.toBeInTheDocument()
  })

  it('preserves a service error instead of supplying sample rows', async () => {
    mocks.campaigns.mockRejectedValue(new Error('service unavailable'))
    mount()
    expect(await screen.findByText('活动列表加载失败')).toBeInTheDocument()
    expect(screen.queryByText(campaignFixtures[0].name)).not.toBeInTheDocument()
    expect(screen.queryByText('还没有营销活动')).not.toBeInTheDocument()
  })

  it('does not reveal old scope rows or allow a create URL after permission revocation', async () => {
    mocks.campaigns.mockResolvedValue([campaignFixtures[0]])
    const page = mount('/campaigns?create=true')
    expect(await screen.findByText(campaignFixtures[0].name)).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    page.updateAuth({ ...devAuthState, tenantId: 'other', permissions: [], hasPermission: () => false })
    expect(screen.getByText('没有查看活动的权限')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.queryByText(campaignFixtures[0].name)).not.toBeInTheDocument()
    expect(mocks.create).not.toHaveBeenCalled()
  })

  it('waits for a real creation response and navigates using its persisted ID', async () => {
    const result = { ...campaignFixtures[0], id: 'server-created-id', name: '接口保存活动', campaignType: 'STANDARD' }
    mocks.create.mockResolvedValue(result)
    mount('/campaigns?create=true')
    await userEvent.type(screen.getByRole('textbox', { name: '活动名称' }), result.name)
    await userEvent.type(screen.getByRole('textbox', { name: '可衡量目标' }), '提升真实会员复购率')
    await userEvent.click(screen.getByRole('button', { name: '创建并进入设计' }))
    expect(await screen.findByText('/designers/offer?campaignId=server-created-id')).toBeInTheDocument()
    expect(mocks.create).toHaveBeenCalledWith(expect.objectContaining({ name: result.name, campaignType: 'STANDARD', organizationId: 'retail-business' }))
  })
})
