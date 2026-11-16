import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { onlineManager, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiProblem } from '../../shared/api/client'
import { ReleaseCenterPage } from './ReleaseCenterPage'
import type { ReleaseView, RollbackCandidate } from '../../shared/api/schemas'

const state = vi.hoisted(() => ({ permissions: ['*'], tenant: 'tenant-a', demo: false }))
vi.mock('../../shared/auth/useAuth', () => ({ useAuth: () => ({ tenantId: state.tenant, subject: 'operator', permissions: state.permissions,
  hasPermission: (permission: string) => state.permissions.includes('*') || state.permissions.includes(permission) }) }))
vi.mock('../../shared/api/client', async (original) => ({ ...await original<typeof import('../../shared/api/client')>(), api: {
  releases: vi.fn(), rollbackCandidates: vi.fn(), rollbackRelease: vi.fn(), activateRelease: vi.fn(), setKillSwitch: vi.fn(), get demoMode() { return state.demo },
} }))
vi.mock('./ReleaseWizard', () => ({ ReleaseWizard: () => null }))
const release = (id: string, generation: number, status: string): ReleaseView => ({ manifest: { manifestId: id, generation, stableGeneration: generation,
  environment: 'test', cell: 'cell-a', runtime: 'journey', namespace: 'main', artifacts: [], signature: 'signed' }, state: status, readyReplicas: 1, readyCapacity: 1 })
const candidate = (generation = 4): RollbackCandidate => ({ manifestId: `target-${generation}`, generation, expiresAt: '2026-12-01T00:00:00Z', artifacts: [] })
function renderPage() {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}><ReleaseCenterPage /></QueryClientProvider>)
}
async function openRollback(id = 'active-9') {
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: `回滚 ${id}` }))
  return user
}
beforeEach(() => {
  vi.resetAllMocks(); state.permissions = ['*']; state.tenant = 'tenant-a'; state.demo = false
  vi.mocked(api.releases).mockResolvedValue([release('staged-10', 10, 'STAGED'), release('active-9', 9, 'ACTIVE'), release('other-slot', 7, 'ACTIVE')])
  vi.mocked(api.rollbackCandidates).mockResolvedValue([candidate()])
})

describe('ReleaseCenter explicit targets', () => {
  it('offers actions on each manifest and never assumes the first row is the desired source', async () => {
    renderPage()
    const user = await openRollback()
    expect(api.rollbackCandidates).toHaveBeenCalledWith('active-9')
    expect(screen.getByRole('button', { name: '回滚 staged-10' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '激活 active-9' })).toBeDisabled()
    expect(screen.queryByLabelText('审批号与操作原因')).not.toBeInTheDocument()
    await user.click(screen.getByRole('checkbox'))
    expect(screen.getByRole('button', { name: '确认执行' })).toBeDisabled()
    await user.click(screen.getByRole('combobox', { name: '回滚目标版本' }))
    await user.click(screen.getByRole('option', { name: '第 4 代 · target-4' }))
    vi.mocked(api.rollbackRelease).mockResolvedValue(release('target-4', 4, 'ACTIVE'))
    await user.click(screen.getByRole('button', { name: '确认执行' }))
    expect(api.rollbackRelease).toHaveBeenCalledWith('active-9', 4, expect.any(String))
    expect(await screen.findByText('回滚已提交 target-4')).toBeInTheDocument()
  })

  it('keeps failures inside the confirmation dialog and reuses the exact command key', async () => {
    renderPage()
    const user = await openRollback()
    await user.click(screen.getByRole('combobox', { name: '回滚目标版本' }))
    await user.click(screen.getByRole('option', { name: '第 4 代 · target-4' }))
    await user.click(screen.getByRole('checkbox'))
    vi.mocked(api.rollbackRelease).mockRejectedValue(new Error('网络中断，结果未知'))
    await user.click(screen.getByRole('button', { name: '确认执行' }))
    const dialog = screen.getByRole('dialog')
    expect(await within(dialog).findByText('网络中断，结果未知')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重试原回滚请求' }))
    expect(vi.mocked(api.rollbackRelease).mock.calls[0]).toEqual(vi.mocked(api.rollbackRelease).mock.calls[1])
  })

  it('can replay the original rollback after reconnect refresh says the source was already rolled back', async () => {
    renderPage(); const user = await openRollback()
    await user.click(screen.getByRole('combobox', { name: '回滚目标版本' }))
    await user.click(screen.getByRole('option', { name: '第 4 代 · target-4' }))
    await user.click(screen.getByRole('checkbox'))
    vi.mocked(api.rollbackRelease).mockRejectedValueOnce(new ApiProblem({ type: 'about:blank', title: 'Network error', status: 0, detail: 'response lost', retryable: true })).mockResolvedValue(release('target-4', 4, 'ACTIVE'))
    await user.click(screen.getByRole('button', { name: '确认执行' }))
    await screen.findByText('上次回滚结果待确认')
    vi.mocked(api.rollbackCandidates).mockRejectedValue(new Error('ROLLBACK_SOURCE_NOT_ACTIVE'))
    act(() => onlineManager.setOnline(false))
    act(() => onlineManager.setOnline(true))
    expect(await screen.findByText('ROLLBACK_SOURCE_NOT_ACTIVE')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '确认执行' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '重试原回滚请求' }))
    expect(await screen.findByText('回滚已提交 target-4')).toBeInTheDocument()
    expect(vi.mocked(api.rollbackRelease).mock.calls[0]).toEqual(vi.mocked(api.rollbackRelease).mock.calls[1])
  })

  it.each(['empty', 'error'])('blocks rollback for %s candidate responses', async (mode) => {
    if (mode === 'empty') vi.mocked(api.rollbackCandidates).mockResolvedValue([])
    else vi.mocked(api.rollbackCandidates).mockRejectedValue(new Error('源发布已经变化'))
    renderPage()
    const user = await openRollback()
    await screen.findByText(mode === 'empty' ? '没有可回滚版本' : '源发布已经变化')
    await user.click(screen.getByRole('checkbox'))
    expect(screen.getByRole('button', { name: '确认执行' })).toBeDisabled()
    expect(api.rollbackRelease).not.toHaveBeenCalled()
  })

  it('ignores candidates from a previously closed source dialog', async () => {
    let old!: (value: RollbackCandidate[]) => void
    vi.mocked(api.rollbackCandidates).mockImplementationOnce(() => new Promise((resolve) => { old = resolve })).mockResolvedValue([candidate(2)])
    renderPage()
    const user = await openRollback()
    expect(screen.getByRole('button', { name: '确认执行' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '取消' }))
    await openRollback('other-slot')
    await act(async () => old([candidate(4)]))
    await user.click(screen.getByRole('combobox', { name: '回滚目标版本' }))
    expect(screen.getByRole('option', { name: '第 2 代 · target-2' })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: '第 4 代 · target-4' })).not.toBeInTheDocument()
  })

  it('keeps the selected source fixed while an operation is pending', async () => {
    let done!: (value: ReleaseView) => void
    vi.mocked(api.activateRelease).mockImplementation(() => new Promise((resolve) => { done = resolve }))
    renderPage(); const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '激活 staged-10' }))
    await user.click(screen.getByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: '确认执行' }))
    expect(screen.getByRole('button', { name: '取消' })).toBeDisabled()
    await user.keyboard('{Escape}')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '回滚 active-9' })).toBeDisabled()
    await act(async () => done(release('staged-10', 10, 'ACTIVE')))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('shows namespace-wide kill scope and sends the actual reason', async () => {
    renderPage(); const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '熔断 active-9 的命名空间' }))
    expect(screen.getByText(/不限于这条清单的环境/)).toBeInTheDocument()
    await user.type(screen.getByLabelText('操作原因'), '隔离测试原因')
    expect(screen.getByLabelText('操作原因')).toHaveValue('隔离测试原因')
    await user.click(screen.getByRole('checkbox'))
    vi.mocked(api.setKillSwitch).mockResolvedValue({ namespace: 'main', enabled: true, reason: '隔离测试原因', updatedBy: 'operator', sequence: 1 })
    await user.click(screen.getByRole('button', { name: '确认执行' }))
    expect(api.setKillSwitch).toHaveBeenCalledWith('main', true, '隔离测试原因', expect.any(String))
  })

  it('prevents operations when the required permission is missing', async () => {
    state.permissions = ['release:read']
    renderPage()
    expect(await screen.findByRole('button', { name: '回滚 active-9' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '激活 staged-10' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '熔断 active-9 的命名空间' })).toBeDisabled()
  })
})
