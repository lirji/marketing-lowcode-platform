import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthStateContext, devAuthState, type AuthState } from '../../shared/auth/auth-context'
import { AssetsPage } from './AssetsPage'
import { ReferralOperationsPage } from '../referral/ReferralOperationsPage'
import { AnalyticsPage } from '../analytics/AnalyticsPage'

const state = vi.hoisted(() => ({ demo: true }))
const calls = vi.hoisted(() => ({ audiencePage: vi.fn(), benefitPage: vi.fn(), templatePage: vi.fn(), fields: vi.fn(), nodeRegistry: vi.fn(), campaigns: vi.fn(), referralParticipants: vi.fn(), referralRelations: vi.fn(), referralRewards: vi.fn(), dashboard: vi.fn(), series: vi.fn(), recomputeAttribution: vi.fn() }))
vi.mock('../../shared/api/client', async original => {
  const actual = await original<typeof import('../../shared/api/client')>()
  return { ...actual, api: { ...actual.api, get demoMode() { return state.demo }, ...calls } }
})
const identityKey = (auth: AuthState) => JSON.stringify([auth.tenantId, auth.subject, auth.organizations, auth.shops, auth.permissions])
function setup(page: 'assets' | 'referrals' | 'analytics', auth = devAuthState) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  const identity = identityKey(auth), scope = [auth.tenantId, auth.subject, auth.organizations, auth.shops, auth.permissions]
  const cachedCampaigns = [{ id: 'campaign-old', name: '旧缓存活动', campaignType: 'REFERRAL' }]
  const pageOf = (items: unknown[]) => ({ pages: [{ items, hasMore: false, nextCursor: null, query: '' }], pageParams: [undefined] })
  client.setQueryData(['audience-assets', ...scope, ''], pageOf([{ segmentId: 'audience-old', name: '旧缓存人群', version: 1, status: 'DRAFT' }]))
  client.setQueryData(['benefit-assets', ...scope, ''], pageOf([{ benefitId: 'benefit-old', name: '旧缓存权益', version: 1, status: 'DRAFT' }]))
  client.setQueryData(['templates', 'pages', identity, ''], pageOf([{ templateId: '旧缓存模板', version: 1, channel: 'SMS', requiredVariables: [], state: 'DRAFT', content: '旧缓存正文' }]))
  client.setQueryData(['fields', identity], [{ fieldId: '旧缓存字段', valueType: 'STRING' }])
  client.setQueryData(['node-registry', identity], [{ stableTypeId: '旧缓存节点', semanticVersion: '1', dialects: [], runtimeTarget: 'ONLINE' }])
  client.setQueryData(['campaigns', auth.tenantId, auth.organizations, auth.shops], cachedCampaigns)
  client.setQueryData(['analytics-campaign-options', identity], cachedCampaigns)
  client.setQueryData(['referral-ops', 'participants', auth.tenantId, auth.organizations, auth.shops, 'campaign-old', undefined], { items: [{ participantId: '旧缓存参与人' }], nextCursor: null })
  render(<QueryClientProvider client={client}><AuthStateContext.Provider value={auth}><MemoryRouter initialEntries={['/referral-operations?campaignId=campaign-old']}>{page === 'assets' ? <AssetsPage /> : page === 'referrals' ? <ReferralOperationsPage /> : <AnalyticsPage />}</MemoryRouter></AuthStateContext.Provider></QueryClientProvider>)
  return client
}
function expectNoApiCalls() { for (const call of Object.values(calls)) expect(call).not.toHaveBeenCalled() }

describe('static asset and reporting boundaries', () => {
  beforeEach(() => { state.demo = true; vi.resetAllMocks() })
  it('hides cached assets and all refresh/detail/write entry points across four tabs', async () => {
    setup('assets')
    for (const [tab, title] of [['Audience 快照', '人群列表需要连接服务'], ['权益与券', '权益列表需要连接服务'], ['消息模板', '模板列表需要连接服务'], ['字段与节点', '字段与节点需要连接服务']]) {
      await userEvent.click(screen.getByRole('tab', { name: tab }))
      expect(screen.getByText(title)).toBeInTheDocument(); expect(screen.queryByText(/旧缓存/)).not.toBeInTheDocument()
      expect(screen.queryByRole('table')).not.toBeInTheDocument(); expect(screen.queryByRole('button', { name: /^刷新/ })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /^查看详情/ })).not.toBeInTheDocument(); expectNoApiCalls()
    }
  })
  it('hides cached referral campaign names and participants with a deep-linked campaign', async () => {
    setup('referrals')
    for (const tab of ['参与人', '关系 / 资格', '奖励', '异常']) {
      await userEvent.click(screen.getByRole('tab', { name: tab })); expect(screen.queryByText(/旧缓存/)).not.toBeInTheDocument()
      expect(screen.queryByRole('table')).not.toBeInTheDocument(); expectNoApiCalls()
    }
  })
  it('keeps analysis data and disabled options isolated without triggering recomputation', () => {
    setup('analytics'); expect(screen.getByText('请连接演示数据源')).toBeInTheDocument(); expect(screen.queryByText(/旧缓存/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重算全部归因' })).toBeDisabled(); expectNoApiCalls()
  })
  it.each(['audience-field:read', 'definition:read'])('shows only authorized registry metadata in live mode: %s', async permission => {
    state.demo = false
    const auth = { ...devAuthState, permissions: [permission], hasPermission: (value: string) => value === permission, hasAnyPermission: (values: string[]) => values.includes(permission) }
    setup('assets', auth)
    expect(screen.getByRole('heading', { name: permission === 'audience-field:read' ? '数据字段' : '低代码节点' })).toBeInTheDocument()
    expect(screen.queryByText(permission === 'audience-field:read' ? '旧缓存节点' : '旧缓存字段')).not.toBeInTheDocument()
    expect(calls[permission === 'audience-field:read' ? 'nodeRegistry' : 'fields']).not.toHaveBeenCalled()
  })
})
