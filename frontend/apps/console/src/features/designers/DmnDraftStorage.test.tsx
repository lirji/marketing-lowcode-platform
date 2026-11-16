import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { DmnDesignerPage } from './DmnDesignerPage'
import { api, ApiProblem } from '../../shared/api/client'
import { newDmnTable, type DmnDraft } from '../../shared/api/dmn'
import { displayTimestamp } from '../../components/dateTime'

const state = vi.hoisted(() => ({ permissions: ['*'], tenant: 'tenant-a', demo: false }))
vi.mock('../../shared/auth/useAuth', () => ({ useAuth: () => ({ tenantId: state.tenant, subject: 'author', permissions: state.permissions,
  hasPermission: (permission: string) => state.permissions.includes('*') || state.permissions.includes(permission) }) }))
vi.mock('../../shared/api/client', async (original) => ({ ...await original<typeof import('../../shared/api/client')>(),
  api: { campaigns: vi.fn(), latestDmnDraft: vi.fn(), saveDmnDraft: vi.fn(), analyzeDmn: vi.fn(), evaluateDmn: vi.fn(), get demoMode() { return state.demo } } }))
const problem = (code: string, status: number) => new ApiProblem({ type: 'about:blank', title: code, detail: code, status, code })
const draft = (version = 1): DmnDraft => ({ campaignId: 'cmp-a', version, contentHash: 'a'.repeat(64), createdBy: 'author', createdAt: '2026-11-16T00:00:00Z', table: { ...newDmnTable(), inputs: [{ name: 'amount', type: 'number' }] } })
async function choose(name = '活动甲') {
  const user = userEvent.setup()
  await user.click(screen.getByRole('combobox', { name: 'DMN 归属活动' }))
  await user.click(await screen.findByRole('option', { name: new RegExp(name) }))
  return user
}
beforeEach(() => {
  vi.resetAllMocks(); state.permissions = ['*']; state.tenant = 'tenant-a'; state.demo = false
  vi.mocked(api.campaigns).mockResolvedValue(['甲', '乙'].map((name, i) => ({ id: i ? 'cmp-b' : 'cmp-a', name: `活动${name}`, objective: 'test', status: 'DRAFT', createdAt: '2026-11-16T00:00:00Z', campaignType: 'STANDARD' })))
  vi.mocked(api.latestDmnDraft).mockRejectedValue(problem('DMN_DRAFT_NOT_FOUND', 404))
})

describe('DMN database draft versions', () => {
  it('binds a previously edited table to a campaign with no draft without losing inputs', async () => {
    render(<DmnDesignerPage />)
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('输入字段 1 名称'), 'amount')
    await choose()
    expect(screen.getByLabelText('输入字段 1 名称')).toHaveValue('amount')
    vi.mocked(api.saveDmnDraft).mockResolvedValue(draft())
    await user.click(screen.getByRole('button', { name: '保存草稿版本' }))
    expect(await screen.findByText('已保存 · v1')).toBeInTheDocument()
    expect(api.saveDmnDraft).toHaveBeenCalledWith('cmp-a', 0, draft().table, expect.any(String))
    expect(screen.getByText((text) => text.includes('保存时间') && text.includes(displayTimestamp(draft().createdAt)))).toBeInTheDocument()
  })

  it('restores latest table and retains edits made while saving the prior revision', async () => {
    vi.mocked(api.latestDmnDraft).mockResolvedValue(draft())
    render(<DmnDesignerPage />)
    const user = await choose()
    await waitFor(() => expect(screen.getByLabelText('输入字段 1 名称')).toHaveValue('amount'))
    await user.type(screen.getByLabelText('规则 1 输出 1'), '"A"')
    let done!: (value: DmnDraft) => void
    vi.mocked(api.saveDmnDraft).mockImplementation(() => new Promise((resolve) => { done = resolve }))
    await user.click(screen.getByRole('button', { name: '保存草稿版本' }))
    await user.type(screen.getByLabelText('规则 1 输出 1'), 'B')
    await act(async () => done({ ...draft(2), table: { ...draft().table, rules: [{ inputs: [''], outputs: ['"A"'] }] } }))
    expect(screen.getByLabelText('规则 1 输出 1')).toHaveValue('"A"B')
    expect(screen.getByText('未保存草稿')).toBeInTheDocument()
    expect(screen.getByText(/基准版本 v2/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '保存草稿版本' })).toBeEnabled()
  })

  it('reuses an uncertain command key until its payload changes', async () => {
    render(<DmnDesignerPage />)
    const user = await choose()
    vi.mocked(api.saveDmnDraft).mockRejectedValue(new Error('网络中断'))
    await user.click(screen.getByRole('button', { name: '保存草稿版本' }))
    await screen.findByText('网络中断')
    await user.click(screen.getByRole('button', { name: '保存草稿版本' }))
    expect(vi.mocked(api.saveDmnDraft).mock.calls[0][3]).toEqual(vi.mocked(api.saveDmnDraft).mock.calls[1][3])
    await user.type(screen.getByLabelText('规则 1 输入 1'), '-')
    await user.click(screen.getByRole('button', { name: '保存草稿版本' }))
    expect(vi.mocked(api.saveDmnDraft).mock.calls[2][3]).not.toEqual(vi.mocked(api.saveDmnDraft).mock.calls[0][3])
  })

  it('preserves conflict input until an explicit reload confirmation', async () => {
    vi.mocked(api.latestDmnDraft).mockResolvedValue(draft())
    render(<DmnDesignerPage />)
    const user = await choose()
    await user.type(screen.getByLabelText('规则 1 输出 1'), 'mine')
    vi.mocked(api.saveDmnDraft).mockRejectedValue(problem('DMN_DRAFT_VERSION_CONFLICT', 409))
    await user.click(screen.getByRole('button', { name: '保存草稿版本' }))
    expect(await screen.findByText('草稿版本冲突')).toBeInTheDocument()
    expect(screen.getByLabelText('规则 1 输出 1')).toHaveValue('mine')
    expect(screen.getByRole('button', { name: '保存草稿版本' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '加载最新版本' }))
    await user.click(screen.getByRole('button', { name: '保留当前编辑' }))
    expect(api.latestDmnDraft).toHaveBeenCalledTimes(1)
    await user.click(screen.getByRole('button', { name: '加载最新版本' }))
    vi.mocked(api.latestDmnDraft).mockResolvedValue(draft(2))
    await user.click(screen.getByRole('button', { name: '放弃修改并加载' }))
    expect(await screen.findByText('已保存 · v2')).toBeInTheDocument()
    expect(screen.getByLabelText('规则 1 输出 1')).toHaveValue('')
  })

  it('confirms dirty campaign switches and never treats a generic 404 as an empty draft', async () => {
    render(<DmnDesignerPage />)
    const user = await choose()
    await user.type(screen.getByLabelText('规则 1 输入 1'), '-')
    await choose('活动乙')
    expect(api.latestDmnDraft).toHaveBeenCalledTimes(1)
    await user.click(screen.getByRole('button', { name: '保留当前编辑' }))
    expect(screen.getByRole('combobox', { name: 'DMN 归属活动' })).toHaveTextContent('活动甲')
    await choose('活动乙')
    vi.mocked(api.latestDmnDraft).mockRejectedValue(problem('CAMPAIGN_NOT_FOUND', 404))
    await user.click(screen.getByRole('button', { name: '放弃修改并加载' }))
    expect(await screen.findByText('CAMPAIGN_NOT_FOUND')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '保存草稿版本' })).toBeDisabled()
    expect(screen.getByLabelText('规则 1 输入 1')).toHaveValue('-')
  })

  it('does not overwrite new edits with a late load response', async () => {
    let done!: (value: DmnDraft) => void
    vi.mocked(api.latestDmnDraft).mockImplementation(() => new Promise((resolve) => { done = resolve }))
    render(<DmnDesignerPage />)
    const user = await choose()
    await user.type(screen.getByLabelText('输入字段 1 名称'), 'newer')
    await act(async () => done(draft()))
    expect(screen.getByLabelText('输入字段 1 名称')).toHaveValue('newer')
    expect(screen.getByText(/加载期间您修改了表格/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '保存草稿版本' })).toBeDisabled()
    vi.mocked(api.latestDmnDraft).mockRejectedValue(problem('DMN_DRAFT_NOT_FOUND', 404))
    await user.click(screen.getByRole('button', { name: '加载最新版本' }))
    expect(screen.getByLabelText('输入字段 1 名称')).toHaveValue('newer')
    expect(screen.getByRole('button', { name: '保存草稿版本' })).toBeEnabled()
  })

  it('can retry a failed first binding without discarding the unbound table', async () => {
    vi.mocked(api.latestDmnDraft).mockRejectedValueOnce(new Error('连接中断'))
    render(<DmnDesignerPage />)
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('输入字段 1 名称'), 'amount')
    await choose()
    expect(await screen.findByText('连接中断')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '加载最新版本' }))
    expect(screen.getByLabelText('输入字段 1 名称')).toHaveValue('amount')
    expect(screen.getByRole('button', { name: '保存草稿版本' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: '放弃修改并加载' })).not.toBeInTheDocument()
  })

  it('discards an old identity response and editor when tenant changes', async () => {
    let done!: (value: DmnDraft) => void
    vi.mocked(api.latestDmnDraft).mockImplementation(() => new Promise((resolve) => { done = resolve }))
    const view = render(<DmnDesignerPage />)
    await choose()
    state.tenant = 'tenant-b'
    view.rerender(<DmnDesignerPage />)
    await act(async () => done(draft()))
    expect(screen.getByLabelText('输入字段 1 名称')).toHaveValue('')
    expect(screen.queryByText('已保存 · v1')).not.toBeInTheDocument()
  })

  it.each(['read-only', 'demo'])('prevents saves in %s mode', async (mode) => {
    if (mode === 'demo') state.demo = true
    else state.permissions = ['campaign:read', 'definition:read']
    render(<DmnDesignerPage />)
    const user = await choose()
    expect(screen.getByRole('button', { name: '保存草稿版本' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '保存草稿版本' }))
    expect(api.saveDmnDraft).not.toHaveBeenCalled()
  })
})
