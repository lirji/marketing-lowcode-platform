import { selectOption } from '../../test/selectOption'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../../shared/auth/AuthContext'
import type { AudienceView, FieldDefinition } from '../../shared/api/schemas'

const mocks = {
  audiences: vi.fn<(...args: unknown[]) => Promise<AudienceView[]>>(),
  previewAudience: vi.fn(),
  snapshotAudience: vi.fn(),
  profileSource: vi.fn(),
  profileEstimate: vi.fn(),
  profileSnapshot: vi.fn(),
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
      previewAudience: () => mocks.previewAudience(),
      snapshotAudience: () => mocks.snapshotAudience(),
      audienceProfileSource: () => mocks.profileSource(),
      audienceProfileEstimate: (...args: unknown[]) => mocks.profileEstimate(...args),
      audienceProfileSnapshot: (...args: unknown[]) => mocks.profileSnapshot(...args),
    },
  }
})

import { AudienceBuilderPage } from './AudienceBuilderPage'

function renderAudience(entry = '/designers/audience') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[entry]}>
        <AuthProvider>
          <AudienceBuilderPage />
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('AudienceBuilderPage', () => {
  it('opens the requested asset instead of the first item and refuses an unknown ID', async () => {
    mocks.audiences.mockResolvedValue([
      { segmentId: 'first', version: 1, name: '第一个人群', status: 'ACTIVE' },
      { segmentId: 'chosen', version: 3, name: '指定人群', status: 'ACTIVE' },
    ])
    mocks.fields.mockResolvedValue([])
    const view = renderAudience('/designers/audience?segmentId=chosen')
    expect(await screen.findByRole('heading', { name: '指定人群' })).toBeInTheDocument()
    expect(screen.queryByDisplayValue('第一个人群')).not.toBeInTheDocument()
    view.unmount()
    renderAudience('/designers/audience?segmentId=missing')
    expect(await screen.findByText('无法打开所选人群')).toBeInTheDocument()
    expect(screen.queryByDisplayValue('第一个人群')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '保存规则' })).toBeDisabled()
  })

  it('offers ordered comparisons only for number and instant fields', async () => {
    mocks.audiences.mockResolvedValue([{ segmentId: 'recent', version: 1, name: 'Recent', status: 'ACTIVE',
      rule: { match: 'ALL', conditions: [{ fieldId: 'joined', operator: 'GT', value: '2026-01-01T00:00:00Z' }] },
    }])
    mocks.fields.mockResolvedValue([{ fieldId: 'joined', valueType: 'INSTANT' }, { fieldId: 'flag', valueType: 'BOOLEAN' }])
    renderAudience()
    const operator = await screen.findByLabelText('条件 1 算子')
    await userEvent.click(operator)
    expect(screen.getByRole('option', { name: /^>$/ })).toBeInTheDocument()
    await userEvent.keyboard('{Escape}')
    await selectOption(screen.getByLabelText('条件 1 字段'), 'flag')
    expect(operator).toHaveTextContent('=')
    await userEvent.click(operator)
    expect(screen.queryByRole('option', { name: /^>$/ })).not.toBeInTheDocument()
    expect(screen.getByRole('option', { name: /^IN$/ })).toBeInTheDocument()
    await userEvent.keyboard('{Escape}')
  })

  beforeEach(() => {
    mocks.audiences.mockReset()
    mocks.fields.mockReset()
    mocks.previewAudience.mockReset()
    mocks.snapshotAudience.mockReset()
    mocks.profileSource.mockReset().mockResolvedValue({ enabled: false, fieldIds: [] })
    mocks.profileEstimate.mockReset()
    mocks.profileSnapshot.mockReset()
  })

  it('never creates browser-generated members while the real profile source is unavailable', async () => {
    mocks.audiences.mockResolvedValue([{ segmentId: 'real-rule', version: 1, name: '已保存规则', status: 'ACTIVE',
      rule: { match: 'ALL', conditions: [{ fieldId: 'level', operator: 'EQ', value: 'GOLD' }] } }])
    mocks.fields.mockResolvedValue([{ fieldId: 'level', valueType: 'STRING' }])
    renderAudience()
    await screen.findByDisplayValue('GOLD')
    expect(await screen.findByText('画像来源尚未接入')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '预估人数' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '保存快照' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: '预估人数' }))
    await userEvent.click(screen.getByRole('button', { name: '保存快照' }))
    expect(mocks.previewAudience).not.toHaveBeenCalled()
    expect(mocks.snapshotAudience).not.toHaveBeenCalled()
    expect(mocks.profileEstimate).not.toHaveBeenCalled()
    expect(mocks.profileSnapshot).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '保存规则' })).toBeEnabled()
  })

  it('previews and snapshots the saved rule via Commerce, and reuses a failed command key on retry', async () => {
    mocks.audiences.mockResolvedValue([{ segmentId: 'member-rule', version: 2, name: '商城会员', status: 'ACTIVE',
      rule: { match: 'ALL', conditions: [{ fieldId: 'member.level', operator: 'EQ', value: 'VIP' }] } }])
    mocks.fields.mockResolvedValue([{ fieldId: 'member.level', valueType: 'STRING' }])
    mocks.profileSource.mockResolvedValue({ enabled: true, fieldIds: ['member.level'] })
    mocks.profileEstimate.mockResolvedValue({ memberCount: 3, asOf: '2026-11-24T08:00:00Z' })
    mocks.profileSnapshot.mockRejectedValueOnce(new Error('timeout')).mockResolvedValueOnce({
      snapshotId: 'commerce-verified', memberCount: 4, asOf: '2026-11-24T08:00:00Z', expiresAt: '2026-11-24T08:05:00Z',
    })
    renderAudience()
    const preview = screen.getByRole('button', { name: '预估人数' })
    const snapshot = screen.getByRole('button', { name: '保存快照' })
    await waitFor(() => expect(preview).toBeEnabled())
    await userEvent.click(preview)
    expect(await screen.findByText(/3 人 · 数据时间/)).toBeInTheDocument()
    expect(mocks.profileEstimate).toHaveBeenCalledWith('member-rule', 2)
    await userEvent.click(snapshot)
    expect(await screen.findByText('快照保存失败')).toBeInTheDocument()
    await userEvent.click(snapshot)
    expect(await screen.findByText(/已保存快照 commerce-verified，共 4 人/)).toBeInTheDocument()
    expect(mocks.profileSnapshot.mock.calls[0]).toEqual(mocks.profileSnapshot.mock.calls[1])
    await userEvent.type(screen.getByLabelText('条件 1 值'), 'X')
    expect(preview).toBeDisabled()
    expect(snapshot).toBeDisabled()
    expect(screen.queryByText(/3 人 · 数据时间/)).not.toBeInTheDocument()
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
    await selectOption(screen.getByLabelText('已保存版本'), '')
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: '新人群' })).toBeInTheDocument()
    })
    expect(screen.queryByDisplayValue('GOLD')).not.toBeInTheDocument()
    expect(screen.getByText('还没有圈选条件')).toBeInTheDocument()
  })
})
