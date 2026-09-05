import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../../shared/auth/AuthContext'
import type { BenefitSku, BenefitView } from '../../shared/api/schemas'

let demoMode = true
const mocks = {
  benefits: vi.fn<(...args: unknown[]) => Promise<BenefitView[]>>(),
  fundingAccounts: vi.fn<(...args: unknown[]) => Promise<unknown[]>>(),
  benefitSkus: vi.fn<(...args: unknown[]) => Promise<BenefitSku[]>>(),
  putBenefit: vi.fn(),
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
      benefits: () => mocks.benefits(),
      fundingAccounts: () => mocks.fundingAccounts(),
      benefitSkus: (status?: string) => mocks.benefitSkus(status),
      putBenefit: (...args: unknown[]) => mocks.putBenefit(...args),
    },
  }
})

import { BenefitEditorPage } from './BenefitEditorPage'

const sku: BenefitSku = {
  skuId: 'sku-active',
  benefitType: 'COUPON',
  faceValueMinor: 8000,
  currency: 'CNY',
  status: 'ACTIVE',
  enabled: true,
  validityType: 'RELATIVE',
  relativeDays: 7,
  usableWeekdays: [1, 2, 3, 4, 5],
  dailyQuota: 1000,
  userLimitPerDay: 1,
  userLimitTotal: 3,
  version: 2,
}

const liveBenefit: BenefitView = {
  benefitId: 'coupon-live',
  version: 3,
  name: '真实权益',
  status: 'DRAFT',
  resourceKey: '',
  benefitSkuId: null,
  policy: {},
}

function renderEditor() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AuthProvider>
          <BenefitEditorPage />
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('BenefitEditorPage SKU binding', () => {
  beforeEach(() => {
    demoMode = true
    mocks.benefits.mockReset().mockResolvedValue([])
    mocks.fundingAccounts.mockReset().mockResolvedValue([])
    mocks.benefitSkus.mockReset().mockResolvedValue([])
    mocks.putBenefit.mockReset()
  })

  it('does not request benefit-skus in demo mode', async () => {
    renderEditor()
    expect(await screen.findByText('演示模式不绑定真实 SKU')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '绑定权益模板' })).toBeInTheDocument()
    expect(mocks.benefitSkus).not.toHaveBeenCalled()
    expect(screen.queryByLabelText('已投放 SKU')).not.toBeInTheDocument()
  })

  it('shows empty state when the live catalog has no ACTIVE templates', async () => {
    demoMode = false
    renderEditor()
    expect(await screen.findByText('权益中台尚无 ACTIVE 模板')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '家电满 500 减 80 券' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: '权益定义' })).toBeInTheDocument()
    expect(mocks.benefitSkus).toHaveBeenCalledWith('ACTIVE')
  })

  it('loads a selected SKU summary and locks validity / face value', async () => {
    demoMode = false
    mocks.benefits.mockResolvedValue([liveBenefit])
    mocks.benefitSkus.mockResolvedValue([sku])
    const user = userEvent.setup()
    renderEditor()
    expect(await screen.findByRole('heading', { name: '真实权益' })).toBeInTheDocument()
    const picker = await screen.findByLabelText('已投放 SKU')
    await user.selectOptions(picker, 'sku-active')
    expect(await screen.findByTestId('template-summary')).toHaveTextContent('领取后 7 天')
    expect(screen.getByTitle('以权益模板为准')).toHaveValue('领取后 7 天')
    expect(screen.getByTitle('以权益模板面额为准')).toHaveValue('CNY 80.00')
    expect(screen.getByLabelText('门槛（分）')).not.toHaveAttribute('readonly')
  })

  it('surfaces catalog errors without inventing SKUs', async () => {
    demoMode = false
    mocks.benefitSkus.mockRejectedValue(new Error('benefit-skus unavailable'))
    renderEditor()
    expect(await screen.findByText('SKU 目录加载失败')).toBeInTheDocument()
    expect(screen.getByText('benefit-skus unavailable')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByLabelText('已投放 SKU')).not.toBeInTheDocument())
  })
})
