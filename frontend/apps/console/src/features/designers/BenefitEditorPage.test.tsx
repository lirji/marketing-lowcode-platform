import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../../shared/auth/AuthContext'
import { ApiProblem } from '../../shared/api/client'
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

function renderEditor(path = '/designers/benefit') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
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

  it('does not seed the 家电券 mock or request SKUs in demo mode', async () => {
    renderEditor()
    expect(await screen.findByRole('heading', { level: 1, name: '权益定义' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '家电满 500 减 80 券' })).not.toBeInTheDocument()
    expect(screen.queryByText('平台营销中心 / CC-PROMO-2026')).not.toBeInTheDocument()
    expect(screen.queryByText('1,000,000')).not.toBeInTheDocument()
    expect(screen.getByText('本货主尚无已投放模板')).toBeInTheDocument()
    expect(mocks.benefitSkus).not.toHaveBeenCalled()
  })

  it('shows empty state when the live catalog has no ACTIVE templates', async () => {
    demoMode = false
    renderEditor()
    expect(await screen.findByText('当前业务租户 retail-cn 下没有权益')).toBeInTheDocument()
    expect(await screen.findByText('本货主尚无已投放模板')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '家电满 500 减 80 券' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: '权益定义' })).toBeInTheDocument()
    expect(mocks.benefitSkus).toHaveBeenCalledWith('ACTIVE')
  })

  it('does not silently open the newest benefit without benefitId', async () => {
    demoMode = false
    mocks.benefits.mockResolvedValue([
      {
        ...liveBenefit,
        benefitId: 'mock-coupon-ha-80',
        name: '家电满 500 减 80 券（系统 Mock）',
        createdAt: '2026-09-04T10:10:19.072156Z',
      },
      {
        ...liveBenefit,
        benefitId: 'coupon-trace-ha-80',
        name: '追踪联调家电满500减80券',
        createdAt: '2026-09-06T03:28:29.460906083Z',
      },
    ])
    mocks.benefitSkus.mockResolvedValue([sku])
    const user = userEvent.setup()
    renderEditor()
    expect(await screen.findByRole('heading', { level: 1, name: '权益定义' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '追踪联调家电满500减80券' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('已有权益')).toHaveValue('')
    expect(await screen.findByRole('option', { name: /coupon-trace-ha-80/ })).toBeInTheDocument()
    await user.type(screen.getByLabelText('筛选已有权益'), 'coupon-trace')
    expect(screen.getByRole('option', { name: /coupon-trace-ha-80/ })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /mock-coupon-ha-80/ })).not.toBeInTheDocument()
  })

  it('opens a benefit only when benefitId is in the URL', async () => {
    demoMode = false
    mocks.benefits.mockResolvedValue([
      { ...liveBenefit, benefitId: 'coupon-trace-ha-80', name: '追踪联调家电满500减80券' },
      liveBenefit,
    ])
    mocks.benefitSkus.mockResolvedValue([sku])
    renderEditor('/designers/benefit?benefitId=coupon-trace-ha-80')
    expect(await screen.findByRole('heading', { name: '追踪联调家电满500减80券' })).toBeInTheDocument()
    expect(screen.getByLabelText('已有权益')).toHaveValue('coupon-trace-ha-80')
  })

  it('shows an inline save next to the SKU bind panel', async () => {
    demoMode = false
    mocks.benefits.mockResolvedValue([liveBenefit])
    mocks.benefitSkus.mockResolvedValue([sku])
    renderEditor('/designers/benefit?benefitId=coupon-live')
    expect(await screen.findByLabelText('已投放 SKU')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: '保存' }).length).toBeGreaterThan(0)
    expect(screen.getByRole('button', { name: '保存策略' })).toBeInTheDocument()
  })

  it('loads a selected SKU summary and locks validity / face value', async () => {
    demoMode = false
    mocks.benefits.mockResolvedValue([liveBenefit])
    mocks.benefitSkus.mockResolvedValue([sku])
    const user = userEvent.setup()
    renderEditor('/designers/benefit?benefitId=coupon-live')
    expect(await screen.findByRole('heading', { name: '真实权益' })).toBeInTheDocument()
    const picker = await screen.findByLabelText('已投放 SKU')
    await user.selectOptions(picker, 'sku-active')
    expect(await screen.findByTestId('template-summary')).toHaveTextContent('领取后 7 天')
    expect(screen.getByTitle('以权益模板为准')).toHaveValue('领取后 7 天')
    expect(screen.getByTitle('以权益模板面额为准')).toHaveValue('CNY 80.00')
    expect(screen.getByLabelText('门槛（分）')).not.toHaveAttribute('readonly')
  })

  it('marks an unsaved SKU selection dirty and blocks type mismatch', async () => {
    demoMode = false
    mocks.benefits.mockResolvedValue([{ ...liveBenefit, policy: { type: 'COUPON' } }])
    mocks.benefitSkus.mockResolvedValue([
      sku,
      { ...sku, skuId: 'sku-cash', benefitType: 'CASH', faceValueMinor: 1 },
    ])
    const user = userEvent.setup()
    renderEditor('/designers/benefit?benefitId=coupon-live')
    const picker = await screen.findByLabelText('已投放 SKU')
    await user.selectOptions(picker, 'sku-cash')
    expect(screen.getByText('有未保存更改')).toBeInTheDocument()
    expect(screen.getByText('权益类型与模板类型不一致')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: '保存' })[0]).toBeDisabled()
  })

  it('surfaces catalog errors without inventing SKUs', async () => {
    demoMode = false
    mocks.benefitSkus.mockRejectedValue(new Error('benefit-skus unavailable'))
    renderEditor()
    expect(await screen.findByText('SKU 目录加载失败')).toBeInTheDocument()
    expect(screen.getByText('benefit-skus unavailable')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByLabelText('已投放 SKU')).not.toBeInTheDocument())
  })

  it('explains a tenant mismatch instead of a generic permission failure', async () => {
    demoMode = false
    mocks.benefitSkus.mockRejectedValue(new ApiProblem({
      type: 'about:blank',
      title: 'Forbidden',
      status: 403,
      detail: 'benefit catalog rejected the delegated business tenant',
      code: 'BENEFIT_CATALOG_TENANT_MISMATCH',
    }))
    renderEditor()
    expect(await screen.findByText('SKU 目录加载失败')).toBeInTheDocument()
    expect(screen.getByText(/BENEFIT_CATALOG_TENANT_MISMATCH/)).toBeInTheDocument()
    expect(screen.getByText(/当前货主 retail-cn/)).toBeInTheDocument()
    expect(screen.queryByText(/权限不足/)).not.toBeInTheDocument()
  })
})
