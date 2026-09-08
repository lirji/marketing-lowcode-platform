import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../../shared/auth/AuthContext'
import type { Campaign } from '../../shared/api/schemas'

const mocks = {
  campaigns: vi.fn<(...args: unknown[]) => Promise<Campaign[]>>(),
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
      campaigns: () => mocks.campaigns(),
    },
  }
})

vi.mock('./LowCodeDesigner', () => ({
  LowCodeDesigner: (props: { title: string; nodes: { id: string; data: { label: string } }[] }) => (
    <div>
      <h1>{props.title}</h1>
      <ul>{props.nodes.map((node) => <li key={node.id}>{node.data.label}</li>)}</ul>
    </div>
  ),
}))

import { OfferDesignerPage } from './OfferDesignerPage'

function renderOffer(path: string, state?: { campaignName?: string }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[{ pathname: '/designers/offer', search: path.includes('?') ? path.slice(path.indexOf('?')) : '', state }]}>
        <AuthProvider>
          <Routes>
            <Route path="/designers/offer" element={<OfferDesignerPage />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('OfferDesignerPage campaign binding', () => {
  beforeEach(() => {
    mocks.campaigns.mockReset().mockResolvedValue([
      {
        id: 'cmp-spring',
        name: '春季会员日',
        objective: '提升复购',
        status: 'DRAFT',
        createdAt: '2026-09-07T00:00:00Z',
      },
    ])
  })

  it('shows the new campaign instead of the 双11 demo offer', async () => {
    renderOffer('/designers/offer?campaignId=cmp-spring', { campaignName: '春季会员日' })
    expect(await screen.findByRole('heading', { name: '春季会员日 · Offer' })).toBeInTheDocument()
    expect(screen.queryByText('双11家电主会场 · Offer')).not.toBeInTheDocument()
    expect(screen.queryByText('家电类目')).not.toBeInTheDocument()
    expect(screen.queryByText('满 500 减 80')).not.toBeInTheDocument()
    expect(screen.getByText('开始')).toBeInTheDocument()
  })

  it('shows a blank draft instead of the 双11 demo when opened from the sidebar', () => {
    renderOffer('/designers/offer')
    expect(screen.getByRole('heading', { name: 'Offer 草稿' })).toBeInTheDocument()
    expect(screen.queryByText('双11家电主会场 · Offer')).not.toBeInTheDocument()
    expect(screen.queryByText('家电类目')).not.toBeInTheDocument()
    expect(screen.getByText('开始')).toBeInTheDocument()
  })
})
