import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../../shared/auth/AuthContext'
import type { AudienceView, BenefitView, FieldDefinition } from '../../shared/api/schemas'

const mocks = {
  audiences: vi.fn<(...args: unknown[]) => Promise<AudienceView[]>>(),
  benefits: vi.fn<(...args: unknown[]) => Promise<BenefitView[]>>(),
  templates: vi.fn<(...args: unknown[]) => Promise<unknown[]>>(),
  fields: vi.fn<(...args: unknown[]) => Promise<FieldDefinition[]>>(),
  nodeRegistry: vi.fn<(...args: unknown[]) => Promise<unknown[]>>(),
}

vi.mock('../../shared/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../shared/api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      get demoMode() {
        return false
      },
      audiences: () => mocks.audiences(),
      benefits: () => mocks.benefits(),
      templates: () => mocks.templates(),
      fields: () => mocks.fields(),
      nodeRegistry: () => mocks.nodeRegistry(),
    },
  }
})

import { AssetsPage } from './AssetsPage'

function renderAssets() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AuthProvider>
          <AssetsPage />
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('AssetsPage', () => {
  beforeEach(() => {
    mocks.audiences.mockReset().mockResolvedValue([])
    mocks.benefits.mockReset().mockResolvedValue([])
    mocks.templates.mockReset().mockResolvedValue([])
    mocks.fields.mockReset().mockResolvedValue([])
    mocks.nodeRegistry.mockReset().mockResolvedValue([])
  })

  it('does not show PLUS 家电 mock assets when the API is empty', async () => {
    renderAssets()
    expect(await screen.findByText('没有人群定义')).toBeInTheDocument()
    expect(screen.queryByText('PLUS 家电高意向')).not.toBeInTheDocument()
    expect(screen.queryByText('1,248,620')).not.toBeInTheDocument()
  })

  it('lists live benefits instead of the 家电满500 mock coupon', async () => {
    mocks.benefits.mockResolvedValue([{
      benefitId: 'coupon-live',
      version: 2,
      name: '真实满减券',
      status: 'DRAFT',
      resourceKey: '',
      benefitSkuId: null,
      policy: {},
    }])
    const user = userEvent.setup()
    renderAssets()
    await user.click(screen.getByRole('tab', { name: '权益与券' }))
    expect(await screen.findByText('真实满减券')).toBeInTheDocument()
    expect(screen.queryByText('家电满500减80券')).not.toBeInTheDocument()
    expect(screen.queryByText('预算 ¥20M')).not.toBeInTheDocument()
  })

  it('filters real assets and opens complete read-only details without losing the query', async () => {
    mocks.benefits.mockResolvedValue([
      { benefitId: 'coupon-live', version: 2, name: '真实满减券', status: 'DRAFT', resourceKey: '', benefitSkuId: 'sku-with-a-long-stable-reference', policy: {} },
      { benefitId: 'coupon-other', version: 1, name: '其他权益', status: 'DRAFT', resourceKey: '', benefitSkuId: null, policy: {} },
    ])
    const user = userEvent.setup()
    renderAssets()
    await user.click(screen.getByRole('tab', { name: '权益与券' }))
    const search = await screen.findByRole('searchbox', { name: '搜索权益' })
    await user.type(search, 'coupon-live')
    expect(screen.queryByText('其他权益')).not.toBeInTheDocument()
    const view = screen.getByRole('button', { name: '查看真实满减券' })
    await user.click(view)
    expect(screen.getByRole('dialog', { name: '真实满减券' })).toHaveTextContent('sku-with-a-long-stable-reference')
    await user.keyboard('{Escape}')
    expect(view).toHaveFocus()
    expect(search).toHaveValue('coupon-live')
  })
})
