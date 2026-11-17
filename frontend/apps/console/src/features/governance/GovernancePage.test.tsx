import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthStateContext, devAuthState, type AuthState } from '../../shared/auth/auth-context'
import { selectOption } from '../../test/selectOption'
import type { ApprovalPage, ApprovalStatus, ApprovalView } from '../../shared/api/schemas'

const mocks = vi.hoisted(() => ({ page: vi.fn(), decide: vi.fn(), demo: false }))
vi.mock('../../shared/api/client', async (original) => {
  const actual = await original<typeof import('../../shared/api/client')>()
  return { ...actual, api: { ...actual.api, approvalPage: mocks.page, decideApproval: mocks.decide, get demoMode() { return mocks.demo } } }
})
import { GovernancePage } from './GovernancePage'
import { approvalPageSchema } from '../../shared/api/schemas'
const item = (id: string, status: ApprovalStatus = 'OPEN'): ApprovalView => ({ caseId: id, definitionId: `definition-${id}`, definitionVersion: 1, submittedBy: 'author', requiredRoles: ['BUSINESS'], approvals: {}, status, updatedAt: '2026-11-17T00:00:00Z' })
const page = (items: ApprovalView[], more = false, status: ApprovalStatus | null = 'OPEN'): ApprovalPage => ({ items, nextCursor: more ? items.at(-1)!.caseId : null, hasMore: more, status })
function fixture(auth: AuthState = devAuthState) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const tree = (value: AuthState) => <QueryClientProvider client={client}><AuthStateContext.Provider value={value}><GovernancePage /></AuthStateContext.Provider></QueryClientProvider>
  const view = render(tree(auth)); return { switchAuth: (next: AuthState) => view.rerender(tree(next)) }
}
describe('approval pagination', () => {
  beforeEach(() => { mocks.demo = false; mocks.page.mockReset().mockResolvedValue(page([item('a')])); mocks.decide.mockReset() })
  it('retains loaded rows after a next-page failure and retries the same cursor', async () => {
    mocks.page.mockResolvedValueOnce(page([item('a'), item('b')], true)).mockRejectedValueOnce(new Error('后页连接中断')).mockResolvedValue(page([item('c')]))
    fixture(); const user = userEvent.setup(); await screen.findByText('已加载 2 项，还有更多')
    await user.click(screen.getByRole('button', { name: '加载更多审核单' })); await screen.findByText('后续审核单加载失败')
    expect(screen.getByRole('button', { name: '查看审核：a' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重试加载更多审核单' })); await screen.findByText('已加载 3 项')
    expect(mocks.page.mock.calls.slice(1).map(([query]) => query.cursor)).toEqual(['b', 'b'])
    expect(screen.queryByRole('button', { name: '加载更多审核单' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '刷新审核队列' })); await waitFor(() => expect(mocks.page).toHaveBeenLastCalledWith({ status: 'OPEN', limit: 25, cursor: undefined }))
  })
  it('filters status on the server and does not label loaded counts as daily or total', async () => {
    mocks.page.mockImplementation(({ status }) => Promise.resolve(page([item(status === 'APPROVED' ? 'older-approved' : 'pending', status)], false, status)))
    fixture(); await screen.findByRole('button', { name: '查看审核：pending' }); await selectOption(screen.getByLabelText('审核状态'), 'APPROVED')
    await screen.findByRole('button', { name: '查看审核：older-approved' }); expect(screen.queryByText('definition-pending')).not.toBeInTheDocument()
    expect(mocks.page).toHaveBeenLastCalledWith({ status: 'APPROVED', limit: 25, cursor: undefined }); expect(screen.queryByText('今日治理态势')).not.toBeInTheDocument()
    const user = userEvent.setup(); await user.click(screen.getByRole('button', { name: '查看审核：older-approved' })); expect(screen.getByRole('button', { name: '确认批准' })).toBeDisabled()
  })
  it('clears selection and cached rows when the current scope loses read permission', async () => {
    const view = fixture(); const user = userEvent.setup(); await user.click(await screen.findByRole('button', { name: '查看审核：a' }))
    view.switchAuth({ ...devAuthState, organizations: ['other-org'], permissions: [], hasPermission: () => false, hasAnyPermission: () => false })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); expect(screen.queryByText('definition-a')).not.toBeInTheDocument(); expect(screen.getByText('没有查看审核单的权限')).toBeInTheDocument(); expect(mocks.page).toHaveBeenCalledTimes(1)
  })
  it('does not show late results from the previous tenant', async () => {
    let finish!: (value: ApprovalPage) => void
    mocks.page.mockImplementationOnce(() => new Promise<ApprovalPage>((resolve) => { finish = resolve })).mockResolvedValue(page([item('new-tenant')]))
    const view = fixture(); await waitFor(() => expect(mocks.page).toHaveBeenCalledTimes(1)); view.switchAuth({ ...devAuthState, tenantId: 'next-tenant' }); await screen.findByRole('button', { name: '查看审核：new-tenant' })
    await act(async () => finish(page([item('old-tenant')]))); expect(screen.queryByText('definition-old-tenant')).not.toBeInTheDocument()
  })
  it('distinguishes initial failure from an empty queue and supports refresh', async () => {
    mocks.page.mockRejectedValueOnce(new Error('服务不可用')).mockResolvedValue(page([])); fixture(); await screen.findByText('审核队列加载失败')
    expect(screen.queryByText('当前筛选没有审核单')).not.toBeInTheDocument(); await userEvent.setup().click(screen.getByRole('button', { name: '刷新审核队列' })); await screen.findByText('当前筛选没有审核单')
  })
  it('keeps DEMO mode read-only and rejects inconsistent paging contracts', () => {
    mocks.demo = true; fixture(); expect(mocks.page).not.toHaveBeenCalled(); expect(screen.getByRole('button', { name: '刷新审核队列' })).toBeDisabled()
    expect(approvalPageSchema.safeParse(page([item('a')], true)).success).toBe(true)
    for (const invalid of [{ ...page([item('a')]), hasMore: true }, { ...page([item('a')], true), nextCursor: 'other' }, page([item('a', 'REJECTED')]), page(Array.from({ length: 101 }, (_, index) => item(String(index))))]) expect(approvalPageSchema.safeParse(invalid).success).toBe(false)
  })
})
