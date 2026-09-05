import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../../shared/auth/AuthContext'
import type { AwardIntentView, Campaign } from '../../shared/api/schemas'

let demoMode = true
const mocks = {
  campaigns: vi.fn<(...args: unknown[]) => Promise<Campaign[]>>(),
  awardIntents: vi.fn<(campaignId: string, query?: { limit?: number; cursor?: string }) => Promise<AwardIntentView[]>>(),
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
      awardIntents: (campaignId: string, query?: { limit?: number; cursor?: string }) => mocks.awardIntents(campaignId, query),
    },
  }
})

import { AwardIntentsPanel, benefitOrderHref, riskDecisionHref } from './AwardIntentsPanel'

const campaign: Campaign = {
  id: 'CMP-1',
  name: '家电活动',
  objective: '转化',
  status: 'ACTIVE',
  createdAt: '2026-09-01T00:00:00Z',
}

const deadIntent: AwardIntentView = {
  intentId: 'ai-dead',
  sourceSystem: 'drools-activity',
  sourceRequestId: 'src-dead-1',
  campaignId: 'CMP-1',
  definitionVersion: 3,
  subjectHash: 'a'.repeat(64),
  deliveryMode: 'CENTER',
  status: 'DEAD',
  deliveryResult: 'CENTER_ENQUEUED',
  attempts: 5,
  benefitOrderNo: null,
  lastError: 'UNKNOWN_MUST_QUERY',
  createdAt: '2026-09-05T01:00:00Z',
  updatedAt: '2026-09-05T01:10:00Z',
  sentAt: null,
}

const blockedIntent: AwardIntentView = {
  intentId: 'ai-blocked',
  sourceSystem: 'drools-activity',
  sourceRequestId: 'src-blocked-1',
  campaignId: 'CMP-1',
  definitionVersion: 3,
  subjectHash: 'b'.repeat(64),
  deliveryMode: 'CENTER',
  status: 'RISK_BLOCKED',
  deliveryResult: null,
  riskAction: 'REJECT',
  riskReason: 'HIT_VELOCITY_LIMIT',
  riskDecisionId: 'dec-9',
  attempts: 0,
  benefitOrderNo: null,
  lastError: '',
  createdAt: '2026-09-05T02:00:00Z',
  updatedAt: '2026-09-05T02:00:00Z',
  sentAt: null,
}

function renderPanel(path = '/operations?tab=awards') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <AuthProvider>
          <AwardIntentsPanel />
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('AwardIntentsPanel', () => {
  beforeEach(() => {
    demoMode = true
    mocks.campaigns.mockReset().mockResolvedValue([campaign])
    mocks.awardIntents.mockReset().mockResolvedValue([])
  })

  it('does not request intents in demo mode', async () => {
    renderPanel('/operations?tab=awards&campaignId=CMP-1')
    expect(await screen.findByText('演示模式不查询真实发放指令')).toBeInTheDocument()
    expect(mocks.awardIntents).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: /提交|发奖|新建 AwardIntent/i })).not.toBeInTheDocument()
  })

  it('does not request intents until a campaign is selected', async () => {
    demoMode = false
    renderPanel()
    expect(await screen.findByText('请先选择活动')).toBeInTheDocument()
    expect(mocks.awardIntents).not.toHaveBeenCalled()
  })

  it('lists intents and keeps DEAD sourceRequestId copyable without an origin', async () => {
    demoMode = false
    mocks.awardIntents.mockResolvedValue([deadIntent])
    renderPanel('/operations?tab=awards&campaignId=CMP-1')
    expect(await screen.findByText('src-dead-1')).toBeInTheDocument()
    expect(screen.getByText('UNKNOWN_MUST_QUERY')).toBeInTheDocument()
    expect(screen.getByText('未配置权益台地址，仅可复制 sourceRequestId')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '打开权益订单' })).not.toBeInTheDocument()
    expect(mocks.awardIntents).toHaveBeenCalledWith('CMP-1', expect.objectContaining({ limit: 20 }))
  })

  it('builds a benefit-console deep link for DEAD intents', () => {
    expect(benefitOrderHref(deadIntent, '')).toBe('')
    expect(benefitOrderHref(deadIntent, 'http://127.0.0.1:8083')).toBe(
      'http://127.0.0.1:8083/orders?q=src-dead-1&sourceSystem=drools-activity',
    )
  })

  it('shows RISK_BLOCKED reason without an award submit form or order link', async () => {
    demoMode = false
    mocks.awardIntents.mockResolvedValue([blockedIntent])
    renderPanel('/operations?tab=awards&campaignId=CMP-1')
    expect(await screen.findByText('src-blocked-1')).toBeInTheDocument()
    expect(screen.getByText('风控拦截')).toBeInTheDocument()
    expect(screen.getByText('风控拒绝，未写出发放指令')).toBeInTheDocument()
    expect(screen.getByText('HIT_VELOCITY_LIMIT')).toBeInTheDocument()
    expect(screen.getByText('未配置风控台地址，仅可复制 sourceRequestId')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '打开权益订单' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '打开风控决策' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /提交|发奖|新建 AwardIntent/i })).not.toBeInTheDocument()
  })

  it('builds a risk-console deep link only for RISK_BLOCKED rows', () => {
    expect(riskDecisionHref(blockedIntent, '')).toBe('')
    expect(riskDecisionHref(deadIntent, 'http://127.0.0.1:15173')).toBe('')
    expect(riskDecisionHref(blockedIntent, 'http://127.0.0.1:15173')).toBe(
      'http://127.0.0.1:15173/decisions?q=src-blocked-1',
    )
  })

  it('requests after the operator picks a campaign', async () => {
    demoMode = false
    const user = userEvent.setup()
    renderPanel()
    expect(await screen.findByRole('option', { name: /家电活动/ })).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('活动'), 'CMP-1')
    await waitFor(() => expect(mocks.awardIntents).toHaveBeenCalledWith('CMP-1', expect.objectContaining({ limit: 20 })))
  })
})
