import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { DmnDesignerPage } from './DmnDesignerPage'
import { api } from '../../shared/api/client'

const permissions = vi.hoisted(() => ({ values: new Set(['*']) }))
vi.mock('../../shared/auth/useAuth', () => ({ useAuth: () => ({ hasPermission: (permission: string) => permissions.values.has('*') || permissions.values.has(permission) }) }))
vi.mock('../../shared/api/client', () => ({ api: { analyzeDmn: vi.fn(), evaluateDmn: vi.fn() } }))
const analysis = { valid: true, gaps: [], overlaps: [], messages: [] }
async function fillTable() {
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('输入字段 1 名称'), 'amount')
  await user.type(screen.getByLabelText('输出字段 1 名称'), 'level')
  await user.type(screen.getByLabelText('规则 1 输入 1'), '>= 100')
  await user.type(screen.getByLabelText('规则 1 输出 1'), '"VIP"')
  return user
}
beforeEach(() => { vi.resetAllMocks(); permissions.values = new Set(['*']) })

describe('DmnDesignerPage', () => {
  it('disables unavailable permissions separately', () => {
    permissions.values = new Set(['definition:read'])
    const view = render(<DmnDesignerPage />)
    expect(screen.getByRole('button', { name: '分析表格' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '运行测试行' })).toBeDisabled()
    permissions.values.add('definition:compile')
    view.rerender(<DmnDesignerPage />)
    expect(screen.getByRole('button', { name: '分析表格' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '运行测试行' })).toBeDisabled()
    expect(api.evaluateDmn).not.toHaveBeenCalled()
  })

  it('starts blank and rejects incomplete drafts before any request', async () => {
    const user = userEvent.setup()
    render(<DmnDesignerPage />)
    expect(screen.getByRole('heading', { name: 'DMN 决策表' })).toBeInTheDocument()
    expect(screen.getByText('未保存草稿')).toBeInTheDocument()
    expect(screen.getByLabelText('规则 1 输入 1')).toHaveValue('')
    await user.click(screen.getByRole('button', { name: '分析表格' }))
    expect(screen.getByText(/请填写字段名称/)).toBeInTheDocument()
    expect(api.analyzeDmn).not.toHaveBeenCalled()
  })

  it('uses actual API analysis and invalidates it after edits', async () => {
    vi.mocked(api.analyzeDmn).mockResolvedValue({ ...analysis, valid: false, overlaps: ['规则 1 和 2 重叠'] })
    render(<DmnDesignerPage />)
    const user = await fillTable()
    await user.click(screen.getByRole('button', { name: '分析表格' }))
    expect(await screen.findByText('规则 1 和 2 重叠')).toBeInTheDocument()
    expect(api.analyzeDmn).toHaveBeenCalledWith({ hitPolicy: 'UNIQUE', inputs: [{ name: 'amount', type: 'number' }], outputs: [{ name: 'level', type: 'string' }], rules: [{ inputs: ['>= 100'], outputs: ['"VIP"'] }] })
    await user.type(screen.getByLabelText('规则 1 输入 1'), '0')
    expect(screen.queryByText('规则 1 和 2 重叠')).not.toBeInTheDocument()
  })

  it('ignores late analysis after the table changes and restores actions', async () => {
    let done!: (value: typeof analysis) => void
    vi.mocked(api.analyzeDmn).mockImplementation(() => new Promise((resolve) => { done = resolve }))
    render(<DmnDesignerPage />)
    const user = await fillTable()
    await user.click(screen.getByRole('button', { name: '分析表格' }))
    await user.type(screen.getByLabelText('规则 1 输入 1'), '0')
    await act(async () => done(analysis))
    expect(screen.getByText('尚未分析')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '分析表格' })).toBeEnabled()
  })

  it('preserves string precision, shows null honestly, and clears result on context edit', async () => {
    vi.mocked(api.evaluateDmn).mockResolvedValue({ success: true, results: { amount: '123456789012.123456', DmnWorkbenchDecision: null }, messages: [] })
    render(<DmnDesignerPage />)
    const user = await fillTable()
    await user.type(screen.getByLabelText('测试输入 1'), '123456789012.123456')
    await user.click(screen.getByRole('button', { name: '运行测试行' }))
    expect(await screen.findByText('无结果值（null）', { selector: 'code' })).toBeInTheDocument()
    expect(screen.getByText('"123456789012.123456"', { selector: 'code' })).toBeInTheDocument()
    expect(api.evaluateDmn).toHaveBeenCalledWith(expect.anything(), { amount: '123456789012.123456' })
    await user.type(screen.getByLabelText('测试输入 1'), '1')
    expect(screen.getByText('尚未求值')).toBeInTheDocument()
  })

  it.each(['null', ''])('renders literal text %j distinctly from no match', async (value) => {
    vi.mocked(api.evaluateDmn).mockResolvedValue({ success: true, results: { DmnWorkbenchDecision: value }, messages: [] })
    render(<DmnDesignerPage />)
    const user = await fillTable()
    await user.click(screen.getByRole('button', { name: '运行测试行' }))
    expect(await screen.findByText(JSON.stringify(value), { selector: 'code' })).toBeInTheDocument()
    expect(screen.queryByText('无结果值（null）', { selector: 'code' })).not.toBeInTheDocument()
  })

  it('preserves input after errors and can retry without fabricating results', async () => {
    vi.mocked(api.analyzeDmn).mockRejectedValueOnce(new Error('容量已满')).mockResolvedValueOnce(analysis)
    render(<DmnDesignerPage />)
    const user = await fillTable()
    await user.click(screen.getByRole('button', { name: '分析表格' }))
    expect(await screen.findByText('容量已满')).toBeInTheDocument()
    expect(screen.getByLabelText('规则 1 输出 1')).toHaveValue('"VIP"')
    expect(screen.queryByText('引擎分析通过')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '分析表格' }))
    expect(await screen.findByText('引擎分析通过')).toBeInTheDocument()
  })

  it('keeps keyboard focus on the same rule while moving it twice', async () => {
    render(<DmnDesignerPage />)
    const user = await fillTable()
    await user.click(screen.getByRole('button', { name: '新增规则行' }))
    await user.click(screen.getByRole('button', { name: '新增规则行' }))
    screen.getByRole('button', { name: '下移规则 1' }).focus()
    await user.keyboard('{Enter}')
    expect(screen.getByRole('button', { name: '下移规则 2' })).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(screen.getByLabelText('规则 3 输入 1')).toHaveValue('>= 100')
  })

  it('keeps rule cells aligned with added/deleted columns and reordered rules', async () => {
    render(<DmnDesignerPage />)
    const user = await fillTable()
    await user.click(screen.getByRole('button', { name: '新增输入字段' }))
    await user.type(screen.getByLabelText('规则 1 输入 2'), '"A"')
    await user.click(screen.getByRole('button', { name: '新增规则行' }))
    await user.type(screen.getByLabelText('规则 2 输入 1'), '< 100')
    await user.click(screen.getByRole('button', { name: '上移规则 2' }))
    expect(screen.getByLabelText('规则 1 输入 1')).toHaveValue('< 100')
    await user.click(screen.getByRole('button', { name: '删除输入字段 1' }))
    expect(screen.getByLabelText('规则 2 输入 1')).toHaveValue('"A"')
    await waitFor(() => expect(screen.queryByLabelText('规则 1 输入 2')).not.toBeInTheDocument())
  })
})
