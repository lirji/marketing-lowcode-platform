import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../../shared/auth/AuthContext'
import type { Campaign } from '../../shared/api/schemas'

vi.mock('../../shared/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../shared/api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      get demoMode() {
        return false
      },
      campaigns: async (): Promise<Campaign[]> => [
        {
          id: 'cmp-spring',
          name: '春季会员日',
          objective: '提升复购',
          status: 'DRAFT',
          createdAt: '2026-09-07T00:00:00Z',
        },
      ],
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

import { JourneyDesignerPage } from './JourneyDesignerPage'

function renderJourney(search = '', state?: { campaignName?: string }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[{ pathname: '/designers/journey', search, state }]}>
        <AuthProvider>
          <Routes>
            <Route path="/designers/journey" element={<JourneyDesignerPage />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('JourneyDesignerPage', () => {
  it('does not show the 加购未支付召回 demo graph', () => {
    renderJourney()
    expect(screen.getByRole('heading', { name: 'Journey 草稿' })).toBeInTheDocument()
    expect(screen.queryByText('加购未支付召回')).not.toBeInTheDocument()
    expect(screen.queryByText('加购事件')).not.toBeInTheDocument()
    expect(screen.queryByText('等待 30m')).not.toBeInTheDocument()
    expect(screen.getByText('开始')).toBeInTheDocument()
  })

  it('uses the campaign name when opened from an activity', async () => {
    renderJourney('?campaignId=cmp-spring', { campaignName: '春季会员日' })
    expect(await screen.findByRole('heading', { name: '春季会员日 · Journey' })).toBeInTheDocument()
  })
})
