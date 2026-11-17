import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthStateContext, devAuthState, type AuthState } from '../../shared/auth/auth-context'
import type { TemplateView } from '../../shared/api/schemas'
import { templateForm, templatePayload, templateValidation } from './templateForm'

const mocks = vi.hoisted(() => ({ list: vi.fn(), save: vi.fn(), demo: false }))
vi.mock('../../shared/api/client', () => ({ api: { get demoMode() { return mocks.demo }, templates: mocks.list, createTemplate: mocks.save } }))
import { TemplateManager } from './TemplateManager'

const template: TemplateView = { templateId: 'welcome-message', version: 1, channel: 'SMS', content: '你好 {{name}}\n原始正文 <script> 保持纯文本', requiredVariables: ['name'], state: 'APPROVED', createdAt: '2026-11-17T00:00:00Z' }
function fixture(identity = devAuthState) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const tree = (auth: AuthState) => <QueryClientProvider client={client}><AuthStateContext.Provider value={auth}><TemplateManager /></AuthStateContext.Provider></QueryClientProvider>
  const view = render(tree(identity)); return { ...view, switchIdentity: (next: AuthState) => view.rerender(tree(next)) }
}
const deferred = <T,>() => { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }
beforeEach(() => { mocks.demo = false; mocks.list.mockReset().mockResolvedValue([template]); mocks.save.mockReset().mockResolvedValue({ ...template, version: 2 }) })

describe('template assets', () => {
  it('shows real content and creates a new version without changing the template ID', async () => {
    fixture(); const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '查看模板 welcome-message' }))
    expect(screen.getByRole('dialog')).toHaveTextContent('原始正文 <script> 保持纯文本')
    await user.click(screen.getByRole('button', { name: '关闭详情' }))
    await user.click(screen.getByRole('button', { name: '编辑模板 welcome-message 新版本' }))
    expect(screen.getByLabelText('模板 ID')).toHaveAttribute('readonly')
    expect(screen.getByLabelText('目标版本')).toHaveValue(2)
    await user.clear(screen.getByLabelText('模板正文')); await user.type(screen.getByLabelText('模板正文'), '第二版正文')
    await user.click(screen.getByRole('button', { name: '保存新版本' }))
    expect(await screen.findByText('模板版本已保存')).toBeInTheDocument()
    expect(mocks.save.mock.calls[0][0]).toEqual({ templateId: template.templateId, version: 2, channel: 'SMS', content: '第二版正文', requiredVariables: ['name'] })
  })
  it('retains inputs, retries unchanged requests with the same key, and changes the key when content changes', async () => {
    mocks.save.mockRejectedValue(new Error('连接中断，请保留输入重试'))
    fixture(); const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '编辑模板 welcome-message 新版本' }))
    await user.click(screen.getByRole('button', { name: '保存新版本' })); await screen.findByText('模板保存失败')
    const first = mocks.save.mock.calls[0]; expect(screen.getByLabelText('模板正文')).toHaveValue(template.content)
    await user.click(screen.getByRole('button', { name: '保存新版本' })); await waitFor(() => expect(mocks.save).toHaveBeenCalledTimes(2)); expect(mocks.save.mock.calls[1][1]).toBe(first[1])
    await user.clear(screen.getByLabelText('模板正文')); await user.type(screen.getByLabelText('模板正文'), '改过的正文')
    await user.click(screen.getByRole('button', { name: '保存新版本' })); await waitFor(() => expect(mocks.save).toHaveBeenCalledTimes(3)); expect(mocks.save.mock.calls[2][1]).not.toBe(first[1])
  })
  it('protects unsaved edits and blocks closing or duplicate submission while pending', async () => {
    const pending = deferred<TemplateView>(); mocks.save.mockReturnValue(pending.promise)
    fixture(); const user = userEvent.setup(); await user.click(await screen.findByRole('button', { name: '编辑模板 welcome-message 新版本' }))
    await user.type(screen.getByLabelText('模板正文'), '修改')
    await user.click(screen.getByRole('button', { name: '取消编辑' })); expect(screen.getByText('当前修改尚未保存。关闭后将丢失这些输入。')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '继续编辑' })); await user.click(screen.getByRole('button', { name: '保存新版本' }))
    expect(screen.getByRole('button', { name: '保存中…' })).toBeDisabled(); await user.keyboard('{Escape}'); expect(screen.getByRole('dialog')).toBeInTheDocument()
    await act(async () => pending.resolve({ ...template, version: 2 })); await screen.findByText('模板版本已保存'); expect(mocks.save).toHaveBeenCalledTimes(1)
  })
  it('clears the old editor and ignores a late saved response after changing tenant', async () => {
    const pending = deferred<TemplateView>(); mocks.save.mockReturnValue(pending.promise)
    const view = fixture(); const user = userEvent.setup(); await user.click(await screen.findByRole('button', { name: '编辑模板 welcome-message 新版本' })); await user.click(screen.getByRole('button', { name: '保存新版本' }))
    mocks.list.mockResolvedValue([]); view.switchIdentity({ ...devAuthState, tenantId: 'other-tenant' })
    expect(await screen.findByText('没有匹配的消息模板')).toBeInTheDocument(); expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    await act(async () => pending.resolve({ ...template, version: 2 })); expect(screen.queryByText('模板版本已保存')).not.toBeInTheDocument()
  })
  it('honors read-only permissions and DEMO without issuing writes or making up data', async () => {
    const view = fixture({ ...devAuthState, permissions: ['template:read'], hasPermission: (p) => p === 'template:read' })
    expect(await screen.findByRole('button', { name: '编辑模板 welcome-message 新版本' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '新建消息模板' })).toBeDisabled()
    view.switchIdentity({ ...devAuthState, permissions: [], hasPermission: () => false }); expect(screen.getByText('没有查看模板的权限')).toBeInTheDocument(); expect(screen.queryByText('welcome-message')).not.toBeInTheDocument()
    expect(mocks.save).not.toHaveBeenCalled()
  })
  it('does not query or save in static DEMO mode', () => {
    mocks.demo = true; fixture()
    expect(mocks.list).not.toHaveBeenCalled(); expect(screen.getByRole('button', { name: '新建消息模板' })).toBeDisabled()
    expect(screen.queryByText('welcome-message')).not.toBeInTheDocument()
  })
  it('validates bounds, variables and EMAIL text without silently rewriting content', () => {
    const form = templateForm(template); expect(templateValidation(form, 1)).toBeUndefined(); expect(templatePayload(form).content).toBe(template.content)
    for (const patch of [{ version: '1' }, { version: '9007199254740992' }, { content: '' }, { content: 'x'.repeat(8001) }, { channel: ' SMS' }, { variables: 'name\nname' }, { variables: 'name,other' }, { channel: 'EMAIL' }]) expect(templateValidation({ ...form, ...patch }, 1)).toBeTruthy()
    expect(templateValidation({ ...form, channel: 'EMAIL', content: '邮件 unsubscribe' }, 1)).toBeUndefined()
  })
  it('keeps conflicting version input editable so the operator can choose another version', async () => {
    mocks.save.mockRejectedValueOnce(new Error('模板版本已存在，原内容未被覆盖'))
    fixture(); const user = userEvent.setup(); await user.click(await screen.findByRole('button', { name: '编辑模板 welcome-message 新版本' })); await user.click(screen.getByRole('button', { name: '保存新版本' })); await screen.findByText('模板保存失败')
    await user.clear(screen.getByLabelText('目标版本')); await user.type(screen.getByLabelText('目标版本'), '3'); await user.click(screen.getByRole('button', { name: '保存新版本' })); await screen.findByText('模板版本已保存')
    expect(mocks.save.mock.calls[1][0].version).toBe(3); expect(mocks.save.mock.calls[1][0].content).toBe(template.content)
  })
  it('supports an empty library and a new version-1 template', async () => {
    mocks.list.mockResolvedValue([]); fixture(); const user = userEvent.setup(); await screen.findByText('没有匹配的消息模板'); await user.click(screen.getByRole('button', { name: '新建消息模板' }))
    const dialog = within(screen.getByRole('dialog')); await user.type(dialog.getByLabelText('模板 ID'), 'new-template'); await user.type(dialog.getByLabelText('渠道标识'), 'PUSH'); await user.type(dialog.getByLabelText('模板正文'), '真实内容')
    await user.click(dialog.getByRole('button', { name: '保存新版本' })); await screen.findByText('模板版本已保存'); expect(mocks.save.mock.calls[0][0]).toMatchObject({ templateId: 'new-template', version: 1, channel: 'PUSH', requiredVariables: [] })
  })
})
