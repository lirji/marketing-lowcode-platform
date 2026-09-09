import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../../shared/auth/AuthContext'
import type { BenefitSku, BenefitView, Campaign, DefinitionBundle } from '../../shared/api/schemas'

let demoMode = true
const mocks = {
  campaigns: vi.fn<(...args: unknown[]) => Promise<Campaign[]>>(),
  latestDefinition: vi.fn<(...args: unknown[]) => Promise<DefinitionBundle>>(),
  benefits: vi.fn<(...args: unknown[]) => Promise<BenefitView[]>>(),
  benefitSkus: vi.fn<(...args: unknown[]) => Promise<BenefitSku[]>>(),
  saveDefinition: vi.fn(),
  validate: vi.fn(),
  simulate: vi.fn(),
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
      latestDefinition: (...args: unknown[]) => mocks.latestDefinition(...args),
      benefits: () => mocks.benefits(),
      benefitSkus: () => mocks.benefitSkus(),
      saveDefinition: (...args: unknown[]) => mocks.saveDefinition(...args),
      validate: (...args: unknown[]) => mocks.validate(...args),
      simulate: (...args: unknown[]) => mocks.simulate(...args),
    },
    optionalResource: async <T,>(load: () => Promise<T>) => {
      try {
        return await load()
      } catch {
        return undefined
      }
    },
  }
})

import { ReferralDesignerPage } from './ReferralDesignerPage'

function renderDesigner(path = '/designers/referral', state?: { campaignName?: string }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[{ pathname: '/designers/referral', search: path.includes('?') ? path.slice(path.indexOf('?')) : '', state }]}>
        <AuthProvider>
          <Routes>
            <Route path="/designers/referral" element={<ReferralDesignerPage />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ReferralDesignerPage', () => {
  beforeEach(() => {
    demoMode = true
    mocks.campaigns.mockReset()
    mocks.latestDefinition.mockReset()
    mocks.benefits.mockReset().mockResolvedValue([])
    mocks.benefitSkus.mockReset().mockResolvedValue([])
    mocks.saveDefinition.mockReset()
    mocks.validate.mockReset()
    mocks.simulate.mockReset()
  })

  it('shows a blank invitation form without production example policy', () => {
    renderDesigner()
    expect(screen.getByRole('heading', { name: '邀请有礼草稿' })).toBeInTheDocument()
    expect(screen.getByText('裂变发布链未接通')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '提交审核' })).toBeDisabled()
    expect(screen.getByDisplayValue('FIRST_VALID_BIND')).toBeInTheDocument()
    expect(screen.queryByDisplayValue('604800')).not.toBeInTheDocument()
    expect(screen.getByText(/不预填 3\/5 人档/)).toBeInTheDocument()
    expect(screen.getByText(/仿真不会写参与人、不会发奖/)).toBeInTheDocument()
  })

  it('uses the bound campaign name instead of a demo title', () => {
    renderDesigner('/designers/referral?campaignId=cmp-ref', { campaignName: '开学邀请有礼' })
    expect(screen.getByRole('heading', { name: '开学邀请有礼 · 邀请有礼' })).toBeInTheDocument()
  })

  it('keeps save and simulate closed in demo mode', async () => {
    const user = userEvent.setup()
    renderDesigner('/designers/referral?campaignId=cmp-ref', { campaignName: '开学邀请有礼' })
    await user.click(screen.getByRole('button', { name: '保存' }))
    expect(mocks.saveDefinition).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '仿真' }))
    expect(mocks.simulate).not.toHaveBeenCalled()
  })
})
