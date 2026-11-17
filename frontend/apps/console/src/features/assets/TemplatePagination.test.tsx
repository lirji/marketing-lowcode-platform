import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { TemplatePage } from '../../shared/api/schemas'
import { devAuthState } from '../../shared/auth/auth-context'
const mocks = vi.hoisted(() => ({ page: vi.fn(), save: vi.fn(), tenant: 'retail-cn', shops: ['shop-a'] }))
vi.mock('../../shared/auth/useAuth', () => ({ useAuth: () => ({ ...devAuthState, tenantId: mocks.tenant, shops: mocks.shops }) }))
vi.mock('../../shared/api/client', () => ({ api: { demoMode: false, templatePage: mocks.page, createTemplate: mocks.save } }))
import { TemplateManager } from './TemplateManager'
const first = { templateId: 'template-a', version: 1, channel: 'SMS', content: '原有正文', requiredVariables: ['name'], state: 'APPROVED' }
const last = { ...first, templateId: 'template-z', content: '完整后页正文', requiredVariables: ['zeta', 'coupon_needle'] }
const firstPage: TemplatePage = { items: [first], nextCursor: first.templateId, hasMore: true, query: '' }
const lastPage: TemplatePage = { items: [last], nextCursor: null, hasMore: false, query: '' }
function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const tree = () => <QueryClientProvider client={client}><TemplateManager /></QueryClientProvider>
  const view = render(tree()); return () => view.rerender(tree())
}
beforeEach(() => { mocks.page.mockReset().mockResolvedValue(firstPage); mocks.save.mockReset(); mocks.tenant = 'retail-cn'; mocks.shops = ['shop-a'] })
describe('template catalog paging', () => {
  it('keeps previous pages on failure, retries, and opens complete details', async () => {
    mocks.page.mockResolvedValueOnce(firstPage).mockRejectedValueOnce(new Error('后页失败')).mockResolvedValueOnce(lastPage)
    mount(); await screen.findByText('template-a'); fireEvent.click(screen.getByRole('button', { name: '加载更多模板' }))
    await screen.findByText('下一页模板加载失败'); expect(screen.getByText('template-a')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '重试下一页模板' })); await screen.findByText('template-z')
    expect(mocks.page).toHaveBeenLastCalledWith({ q: '', limit: 25, cursor: 'template-a' })
    fireEvent.click(screen.getByRole('button', { name: '查看模板 template-z' })); expect(screen.getByRole('dialog')).toHaveTextContent('完整后页正文')
    expect(screen.getByRole('dialog')).toHaveTextContent('zeta, coupon_needle')
  })
  it('ignores a late old query and keeps server search when a saved version refreshes the list', async () => {
    let resolve!: (value: TemplatePage) => void
    let current = last
    mocks.page.mockImplementationOnce(() => new Promise<TemplatePage>(done => { resolve = done }))
      .mockImplementation(({ q }) => Promise.resolve({ ...lastPage, items: [current], query: q }))
    mocks.save.mockImplementation(async payload => { current = { ...last, ...payload }; return current })
    mount(); fireEvent.change(screen.getByRole('searchbox', { name: '搜索消息模板' }), { target: { value: 'coupon_needle' } })
    await screen.findByText('template-z'); await act(async () => resolve(firstPage)); expect(screen.queryByText('template-a')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '编辑模板 template-z 新版本' })); fireEvent.click(screen.getByRole('button', { name: '保存新版本' }))
    await screen.findByText('模板版本已保存'); await screen.findByRole('cell', { name: 'v2' })
    expect(screen.getByRole('searchbox', { name: '搜索消息模板' })).toHaveValue('coupon_needle')
    expect(mocks.page).toHaveBeenLastCalledWith({ q: 'coupon_needle', limit: 25, cursor: undefined })
    expect(mocks.save).toHaveBeenCalledTimes(1)
  })
  it('clears details and page state when the permitted shop scope changes', async () => {
    mocks.page.mockResolvedValueOnce(firstPage).mockResolvedValue({ items: [], nextCursor: null, hasMore: false, query: '' })
    const refresh = mount(); await screen.findByText('template-a'); fireEvent.click(screen.getByRole('button', { name: '查看模板 template-a' }))
    mocks.shops = ['shop-b']; refresh(); await screen.findByText('没有匹配的消息模板')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); expect(screen.queryByText('template-a')).not.toBeInTheDocument()
  })
  it('distinguishes an initial error from an empty result and retries it', async () => {
    mocks.page.mockRejectedValueOnce(new Error('服务不可用')).mockResolvedValue({ items: [], nextCursor: null, hasMore: false, query: '' })
    mount(); await screen.findByText('模板加载失败'); expect(screen.queryByText('没有匹配的消息模板')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '重试模板查询' })); await waitFor(() => expect(screen.getByText('没有匹配的消息模板')).toBeInTheDocument())
  })
})
