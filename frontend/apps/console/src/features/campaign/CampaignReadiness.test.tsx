import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { DefinitionBundle, ReleaseView } from '../../shared/api/schemas'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthStateContext, devAuthState } from '../../shared/auth/auth-context'
import { CampaignCreateReadiness, CampaignReadiness } from './CampaignReadiness'
import { definitionsHaveRelease } from './campaignReleaseReadiness'

function definition(definitionId: string, version: number): DefinitionBundle {
  return { definitionId, version } as DefinitionBundle
}

function release(definitionId: string, version: number): ReleaseView {
  return {
    manifest: {
      manifestId: `manifest-${definitionId}`,
      cell: 'cell-a',
      generation: 1,
      artifacts: [{ definitionId, definitionVersion: version }],
    },
    state: 'STAGED',
    readyReplicas: 0,
    readyCapacity: 0,
  } as ReleaseView
}

describe('CampaignCreateReadiness', () => {
  it('lists required campaign assets as not ready without inventing data', () => {
    render(<CampaignCreateReadiness />)
    expect(screen.getByLabelText('活动就绪清单')).toBeInTheDocument()
    expect(screen.getByText(/权益：已绑定 ACTIVE SKU/)).toBeInTheDocument()
    expect(screen.getByText(/发布：在发布中心编译并暂存本活动定义/)).toBeInTheDocument()
    expect(screen.getAllByText('未就绪').length).toBeGreaterThanOrEqual(5)
  })

  it('does not require Offer or Journey for invitation campaigns', () => {
    render(<CampaignCreateReadiness designIntent="REFERRAL" />)
    expect(screen.getByText(/REFERRAL_RELEASE_NOT_AVAILABLE/)).toBeInTheDocument()
    expect(screen.queryByText(/Offer：该活动已有决策定义/)).not.toBeInTheDocument()
    expect(screen.getAllByText('未就绪').length).toBeGreaterThanOrEqual(5)
  })

  it('does not treat another campaign release as ready', () => {
    const required = [definition('offer-campaign-42', 3), definition('journey-campaign-42', 2)]
    expect(definitionsHaveRelease(required, [release('offer-other-campaign', 3), release('journey-other-campaign', 2)])).toBe(false)
    expect(definitionsHaveRelease(required, [release('offer-campaign-42', 3), release('journey-campaign-42', 2)])).toBe(true)
  })
})


const mocks = vi.hoisted(() => ({ releasePage: vi.fn(), latestDefinition: vi.fn() }))
vi.mock('../../shared/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../shared/api/client')>()
  return { ...actual, api: { ...actual.api, demoMode: false, releasePage: mocks.releasePage, latestDefinition: mocks.latestDefinition,
    benefits: async () => [], benefitSkus: async () => [], audiences: async () => [] } }
})

describe('campaign release traversal', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mocks.latestDefinition.mockImplementation((_id, dialect) => Promise.resolve(dialect === 'OFFER_DECISION_DAG' ? definition('offer-42', 3) : dialect === 'JOURNEY_STATE_MACHINE' ? definition('journey-42', 2) : undefined))
  })
  const wrap = (canRead = true) => <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AuthStateContext.Provider value={{ ...devAuthState, hasPermission: (p) => canRead || p !== 'release:read' }}><CampaignReadiness campaignId="campaign-42" /></AuthStateContext.Provider></QueryClientProvider>

  it('keeps incomplete results unknown, retains the first page after failure, then finds older matching versions', async () => {
    let fail = true
    mocks.releasePage.mockImplementation(({ cursor }) => cursor
      ? fail ? Promise.reject(new Error('next page unavailable')) : Promise.resolve({ items: [release('offer-42', 3), release('journey-42', 2)], nextCursor: null, hasMore: false, state: null })
      : Promise.resolve({ items: [release('other', 1)], nextCursor: 'manifest-other', hasMore: true, state: null }))
    render(wrap()); const user = userEvent.setup()
    await screen.findByText('发布（尚未核实完整清单）')
    expect(screen.getByText('待核实')).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: '继续核对发布清单' }))
    await screen.findByText('next page unavailable')
    expect(screen.getByText('已核对 1 条发布清单')).toBeInTheDocument()
    fail = false
    await user.click(screen.getByRole('button', { name: '重试后续发布清单' }))
    await screen.findByText('发布（已发现对应版本的暂存或激活清单）')
    await waitFor(() => expect(screen.queryByText('待核实')).not.toBeInTheDocument())
    expect(screen.getByText('已核对 3 条发布清单')).toBeInTheDocument()
    expect(mocks.releasePage).toHaveBeenLastCalledWith({ state: undefined, limit: 25, cursor: 'manifest-other' })
  })

  it('does not query releases without permission or claim the campaign is unpublished', async () => {
    render(wrap(false))
    await screen.findByText('发布（缺少 release:read）')
    expect(mocks.releasePage).not.toHaveBeenCalled()
    expect(screen.getByText('待核实')).toBeInTheDocument()
    expect(screen.queryByText('发布（未在发布中心编译）')).not.toBeInTheDocument()
  })
})
