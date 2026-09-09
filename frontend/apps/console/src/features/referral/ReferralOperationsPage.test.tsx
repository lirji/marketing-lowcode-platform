import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../../shared/auth/AuthContext'
import type { Campaign, ReferralParticipantPage, ReferralRewardPage } from '../../shared/api/schemas'

let demoMode = true
const mocks = {
  campaigns: vi.fn<(...args: unknown[]) => Promise<Campaign[]>>(),
  referralParticipants: vi.fn<(...args: unknown[]) => Promise<ReferralParticipantPage>>(),
  referralRelations: vi.fn(),
  referralRewards: vi.fn<(...args: unknown[]) => Promise<ReferralRewardPage>>(),
}

vi.mock('../../shared/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../shared/api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      get demoMode() {
        return demoMode
      },
      campaigns: () => mocks.campaigns(),
      referralParticipants: (...args: unknown[]) => mocks.referralParticipants(...args),
      referralRelations: (...args: unknown[]) => mocks.referralRelations(...args),
      referralRewards: (...args: unknown[]) => mocks.referralRewards(...args),
    },
  }
})

import { ReferralOperationsPage } from './ReferralOperationsPage'

function renderPage(search = '') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/referral-operations${search}`]}>
        <AuthProvider>
          <ReferralOperationsPage />
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ReferralOperationsPage', () => {
  beforeEach(() => {
    demoMode = true
    mocks.campaigns.mockReset()
    mocks.referralParticipants.mockReset()
    mocks.referralRelations.mockReset()
    mocks.referralRewards.mockReset()
  })

  it('does not invent participants in demo mode', async () => {
    const user = userEvent.setup()
    renderPage()
    expect(screen.getByRole('heading', { name: '邀请有礼运营台' })).toBeInTheDocument()
    expect(screen.getByText('演示模式')).toBeInTheDocument()
    expect(screen.getByText('数据水位：未知')).toBeInTheDocument()
    expect(screen.queryByText('已到账')).not.toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: '异常' }))
    expect(screen.getByText('异常待接入')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('renders participants from the live query without coercing null counts', async () => {
    demoMode = false
    mocks.campaigns.mockResolvedValue([{
      id: 'campaign',
      name: '邀请有礼',
      objective: '裂变',
      status: 'ACTIVE',
      createdAt: '2026-09-01T00:00:00Z',
      campaignType: 'REFERRAL',
    }])
    mocks.referralParticipants.mockResolvedValue({
      items: [{
        participantId: 'p-1',
        campaignId: 'campaign',
        organizationId: 'org',
        shopId: 'shop',
        definitionId: 'def',
        definitionVersion: 1,
        generation: 1,
        state: 'ACTIVE',
        createdAt: '2026-09-01T00:00:00Z',
        validCount: null,
        everQualifiedCount: null,
        progressRevision: null,
      }],
      nextCursor: null,
      asOf: '2026-09-08T04:00:00Z',
      consistency: 'LIVE_DATABASE',
    })
    renderPage('?campaignId=campaign&tab=participants')
    expect(await screen.findByText('p-1')).toBeInTheDocument()
    expect(screen.getAllByText('未计算').length).toBeGreaterThan(0)
    expect(screen.getByText(/LIVE_DATABASE/)).toBeInTheDocument()
    expect(screen.queryByText('已到账')).not.toBeInTheDocument()
  })

  it('labels accepted rewards as local accept, not paid', async () => {
    demoMode = false
    mocks.campaigns.mockResolvedValue([{
      id: 'campaign',
      name: '邀请有礼',
      objective: '裂变',
      status: 'ACTIVE',
      createdAt: '2026-09-01T00:00:00Z',
      campaignType: 'REFERRAL',
    }])
    mocks.referralRewards.mockResolvedValue({
      items: [{
        rewardId: 'a'.repeat(64),
        participantId: 'p-1',
        relationId: 'r-1',
        role: 'INVITER',
        mode: 'PER_RELATION',
        ruleId: 'inviter',
        threshold: 1,
        entitlementState: 'ELIGIBLE',
        authorizationState: 'NONE',
        riskState: 'PENDING',
        deliveryState: 'ACCEPTED',
        compensationState: 'NONE',
        quotaState: 'WAIT_QUOTA',
        revision: 1,
        createdAt: '2026-09-08T00:00:00Z',
      }],
      nextCursor: null,
      asOf: '2026-09-08T04:00:00Z',
      consistency: 'LIVE_DATABASE',
    })
    renderPage('?campaignId=campaign&tab=rewards')
    expect(await screen.findByText('本地受理，非到账')).toBeInTheDocument()
    expect(screen.queryByText('已到账')).not.toBeInTheDocument()
  })
})
