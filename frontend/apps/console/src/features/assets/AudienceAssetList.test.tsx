import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AudiencePage } from '../../shared/api/schemas'
import { devAuthState } from '../../shared/auth/auth-context'

const mocks = vi.hoisted(() => ({ page: vi.fn(), tenant: 'retail-cn' }))
vi.mock('../../shared/auth/useAuth', () => ({ useAuth: () => ({ ...devAuthState, tenantId: mocks.tenant }) }))
vi.mock('../../shared/api/client', async original => {
  const actual = await original<typeof import('../../shared/api/client')>()
  return { ...actual, api: { ...actual.api, demoMode: false, audiencePage: mocks.page } }
})
import { AssetsPage } from './AssetsPage'

const first = { segmentId: 'segment-a', version: 1, name: '第一页人群', status: 'ACTIVE', ruleHash: 'hash-a', createdAt: '2026-10-18T16:30:00Z' }
const last = { ...first, segmentId: 'segment-z', name: '后页目标人群', ruleHash: 'hash-z' }
const firstPage: AudiencePage = { items: [first], nextCursor: 'segment-a', hasMore: true, query: '' }
const lastPage: AudiencePage = { items: [last], nextCursor: null, hasMore: false, query: '' }
function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const tree = () => <QueryClientProvider client={client}><MemoryRouter><AssetsPage /></MemoryRouter></QueryClientProvider>
  const result = render(tree()); return { rerender: () => result.rerender(tree()) }
}

describe('audience assets use server pagination', () => {
  beforeEach(() => { mocks.page.mockReset().mockResolvedValue(firstPage); mocks.tenant = 'retail-cn' })
  it('keeps earlier results when the next page fails, then retries and opens full details', async () => {
    mocks.page.mockResolvedValueOnce(firstPage).mockRejectedValueOnce(new Error('分页暂时失败')).mockResolvedValueOnce(lastPage)
    const user = userEvent.setup(); mount()
    await screen.findByText('第一页人群')
    await user.click(screen.getByRole('button', { name: '加载更多人群' }))
    await screen.findByText('下一页人群加载失败')
    expect(screen.getByText('第一页人群')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重试下一页人群' }))
    await screen.findByText('后页目标人群')
    expect(mocks.page).toHaveBeenLastCalledWith({ q: '', limit: 25, cursor: 'segment-a' })
    expect(screen.getByText(/已加载 2 项/)).toHaveTextContent('当前搜索已全部加载')
    const button = screen.getByRole('button', { name: '查看后页目标人群' })
    await user.click(button)
    expect(screen.getByRole('dialog')).toHaveTextContent('hash-z')
    await user.keyboard('{Escape}'); expect(button).toHaveFocus()
    expect(screen.getByRole('link', { name: '编辑后页目标人群' })).toHaveAttribute('href', '/designers/audience?segmentId=segment-z')
  })
  it('searches beyond loaded rows and resets the cursor for a new query', async () => {
    mocks.page.mockImplementation(({ q }) => Promise.resolve(q ? { ...lastPage, query: q } : firstPage))
    mount(); await screen.findByText('第一页人群')
    screen.getByRole('searchbox', { name: '搜索人群' }).focus()
    fireEvent.change(screen.getByRole('searchbox', { name: '搜索人群' }), { target: { value: '已生效' } })
    await screen.findByText('后页目标人群')
    expect(screen.queryByText('第一页人群')).not.toBeInTheDocument()
    expect(mocks.page).toHaveBeenLastCalledWith({ q: '已生效', limit: 25, cursor: undefined })
    expect(screen.getByRole('searchbox', { name: '搜索人群' })).toHaveFocus()
  })
  it('ignores a late previous query and clears old details when the tenant changes', async () => {
    let resolve!: (value: AudiencePage) => void
    mocks.page.mockImplementationOnce(() => new Promise<AudiencePage>(done => { resolve = done }))
      .mockResolvedValueOnce({ ...lastPage, query: 'target' }).mockResolvedValueOnce({ items: [], nextCursor: null, hasMore: false, query: '' })
    const view = mount()
    fireEvent.change(screen.getByRole('searchbox', { name: '搜索人群' }), { target: { value: 'target' } })
    await screen.findByText('后页目标人群')
    await act(async () => resolve(firstPage))
    expect(screen.queryByText('第一页人群')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '查看后页目标人群' }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    mocks.tenant = 'other-tenant'; view.rerender()
    await screen.findByText('没有人群定义')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.queryByText('后页目标人群')).not.toBeInTheDocument()
  })
  it('shows initial failure distinctly from an empty result and retains the search on retry', async () => {
    mocks.page.mockRejectedValueOnce(new Error('连接失败')).mockResolvedValueOnce({ items: [], nextCursor: null, hasMore: false, query: '' })
    mount(); await screen.findByText('人群加载失败')
    expect(screen.queryByText('没有人群定义')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '重试人群查询' }))
    await waitFor(() => expect(screen.getByText('没有人群定义')).toBeInTheDocument())
  })
})
