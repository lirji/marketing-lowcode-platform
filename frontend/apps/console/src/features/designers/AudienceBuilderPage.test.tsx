import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../../shared/auth/AuthContext'
import type { AudienceView, FieldDefinition } from '../../shared/api/schemas'

const mocks = {
  audiences: vi.fn<(...args: unknown[]) => Promise<AudienceView[]>>(),
  fields: vi.fn<(...args: unknown[]) => Promise<FieldDefinition[]>>(),
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
      fields: () => mocks.fields(),
    },
  }
})

import { AudienceBuilderPage } from './AudienceBuilderPage'

function renderAudience() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AuthProvider>
          <AudienceBuilderPage />
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('AudienceBuilderPage', () => {
  beforeEach(() => {
    mocks.audiences.mockReset()
    mocks.fields.mockReset()
  })

  it('does not seed PLUS 家电 mock rules when the API is empty', async () => {
    mocks.audiences.mockResolvedValue([])
    mocks.fields.mockResolvedValue([])
    renderAudience()
    expect(await screen.findByRole('heading', { name: '新人群' })).toBeInTheDocument()
    expect(screen.queryByDisplayValue('PLUS 家电高意向人群')).not.toBeInTheDocument()
    expect(screen.queryByText('近 90 天家电订单')).not.toBeInTheDocument()
    expect(screen.queryByText('1,248,620')).not.toBeInTheDocument()
    expect(screen.queryByText('近 30 天已退款用户')).not.toBeInTheDocument()
    expect(screen.getByText('还没有圈选条件')).toBeInTheDocument()
  })

  it('hydrates the first saved audience from the API', async () => {
    mocks.audiences.mockResolvedValue([{
      segmentId: 'seg-live',
      version: 3,
      name: '华北高复购',
      status: 'ACTIVE',
      rule: {
        match: 'ALL',
        conditions: [{ fieldId: 'member.level', operator: 'EQ', value: 'GOLD' }],
      },
    }])
    mocks.fields.mockResolvedValue([{ fieldId: 'member.level', owner: 'member', classification: 'INTERNAL', maxAgeSeconds: 300 }])
    renderAudience()
    expect(await screen.findByRole('heading', { name: '华北高复购' })).toBeInTheDocument()
    expect(screen.getByDisplayValue('GOLD')).toBeInTheDocument()
    expect(screen.queryByDisplayValue('PLUS')).not.toBeInTheDocument()
  })

  it('starts a blank draft when switching to 新人群', async () => {
    mocks.audiences.mockResolvedValue([{
      segmentId: 'seg-live',
      version: 3,
      name: '华北高复购',
      status: 'ACTIVE',
      rule: { match: 'ALL', conditions: [{ fieldId: 'member.level', operator: 'EQ', value: 'GOLD' }] },
    }])
    mocks.fields.mockResolvedValue([{ fieldId: 'member.level' }])
    renderAudience()
    expect(await screen.findByDisplayValue('GOLD')).toBeInTheDocument()
    await userEvent.selectOptions(screen.getByLabelText('已保存版本'), '')
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: '新人群' })).toBeInTheDocument()
    })
    expect(screen.queryByDisplayValue('GOLD')).not.toBeInTheDocument()
    expect(screen.getByText('还没有圈选条件')).toBeInTheDocument()
  })
})
