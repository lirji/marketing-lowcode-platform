import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../shared/api/client'
import type { JourneySimulation } from '../../shared/api/schemas'
import { JourneySimulationDialog } from './JourneySimulationDialog'

const state = vi.hoisted(() => ({ allowed: true, demo: false }))
vi.mock('../../shared/auth/useAuth', () => ({ useAuth: () => ({ hasPermission: () => state.allowed }) }))
vi.mock('../../shared/api/client', async (original) => ({ ...await original<typeof import('../../shared/api/client')>(), api: {
  simulateJourney: vi.fn(), get demoMode() { return state.demo },
} }))
const result: JourneySimulation = { kind: 'JOURNEY_SIMULATION', definitionId: 'journey', version: 2, stopReason: 'WAITING', status: 'WAITING', currentNodeId: 'wait',
  elapsedSeconds: 0, remainingEvents: 0, failureCode: '', pendingTimers: [{ nodeId: 'wait', dueOffsetSeconds: 7200 }],
  steps: [{ sequence: 1, signal: 'START', eventType: '', offsetSeconds: 0, fromNodeId: 'trigger', toNodeId: 'wait', status: 'WAITING', commands: [] }] }
function mount() { return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { mutations: { retry: false } } })}><JourneySimulationDialog definitionId="journey" version={2} onClose={vi.fn()} /></QueryClientProvider>) }

describe('Journey simulation inputs and truthful results', () => {
  beforeEach(() => { vi.mocked(api.simulateJourney).mockReset(); state.allowed = true; state.demo = false; vi.mocked(api.simulateJourney).mockResolvedValue(result) })
  it('submits explicit input and displays actual waiting timer, without a price or executed grant', async () => {
    mount(); fireEvent.click(screen.getByRole('button', { name: '运行仿真' }))
    await screen.findByText('模拟仍在等待')
    expect(api.simulateJourney).toHaveBeenCalledWith('journey', 2, { initialVariables: {}, horizonSeconds: 3600, events: [] })
    expect(screen.getByRole('table', { name: '等待中的计时器' })).toHaveTextContent('7200')
    expect(screen.getByText('本次没有生成动作指令。')).toBeInTheDocument()
    expect(screen.queryByText(/优惠.*分/)).not.toBeInTheDocument()
  })
  it('keeps keyboard focus while typing variables and sends event attributes as strings', async () => {
    const user = userEvent.setup(); mount()
    await user.click(screen.getByRole('button', { name: '添加初始变量' }))
    await user.type(screen.getByRole('textbox', { name: '初始变量 1 属性名' }), 'vip')
    await user.type(screen.getByRole('textbox', { name: '初始变量 1 属性值' }), 'yes')
    await user.click(screen.getByRole('button', { name: '添加事件' }))
    await user.type(screen.getByRole('textbox', { name: '事件 1 类型' }), 'ORDER_PAID')
    fireEvent.change(screen.getByRole('spinbutton', { name: '事件 1 发生时间（秒）' }), { target: { value: '10' } })
    await user.click(screen.getByRole('button', { name: '运行仿真' }))
    await waitFor(() => expect(api.simulateJourney).toHaveBeenCalledWith('journey', 2, { initialVariables: { vip: 'yes' }, horizonSeconds: 3600, events: [{ offsetSeconds: 10, eventType: 'ORDER_PAID', attributes: {} }] }))
  })
  it('blocks invalid duplicate properties and out of order events', () => {
    mount(); fireEvent.click(screen.getByRole('button', { name: '添加初始变量' })); fireEvent.click(screen.getByRole('button', { name: '添加初始变量' }))
    for (const n of [1, 2]) fireEvent.change(screen.getByRole('textbox', { name: `初始变量 ${n} 属性名` }), { target: { value: 'vip' } })
    expect(screen.getByRole('button', { name: '运行仿真' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '删除初始变量 2' }))
    for (const n of [1, 2]) { fireEvent.click(screen.getByRole('button', { name: '添加事件' })); fireEvent.change(screen.getByRole('textbox', { name: `事件 ${n} 类型` }), { target: { value: 'PAID' } }) }
    fireEvent.change(screen.getByRole('spinbutton', { name: '事件 1 发生时间（秒）' }), { target: { value: '10' } })
    expect(screen.getByRole('button', { name: '运行仿真' })).toBeDisabled()
    expect(api.simulateJourney).not.toHaveBeenCalled()
  })
  it('does not show late result after input edits and keeps input on failure for retry', async () => {
    let resolve!: (value: JourneySimulation) => void
    vi.mocked(api.simulateJourney).mockImplementationOnce(() => new Promise((done) => { resolve = done }))
    mount(); fireEvent.click(screen.getByRole('button', { name: '运行仿真' }))
    fireEvent.change(screen.getByRole('spinbutton', { name: '模拟窗口（秒）' }), { target: { value: '60' } })
    await waitFor(() => expect(api.simulateJourney).toHaveBeenCalledTimes(1))
    await act(async () => resolve(result))
    await screen.findByRole('button', { name: '运行仿真' })
    expect(screen.queryByRole('region', { name: 'Journey 仿真结果' })).not.toBeInTheDocument()
    vi.mocked(api.simulateJourney).mockRejectedValueOnce(new Error('暂时不可用'))
    fireEvent.click(screen.getByRole('button', { name: '运行仿真' }))
    await screen.findByText('Journey 仿真失败')
    expect(screen.getByRole('spinbutton', { name: '模拟窗口（秒）' })).toHaveValue(60)
    fireEvent.click(screen.getByRole('button', { name: '运行仿真' }))
    await screen.findByText('模拟仍在等待')
  })
  it('shows truncation and proposed commands without claiming real execution', async () => {
    vi.mocked(api.simulateJourney).mockResolvedValue({ ...result, stopReason: 'TRUNCATED', steps: [{ ...result.steps[0], commands: [{ commandId: 'sim-id', nodeId: 'grant', type: 'GRANT' }] }] })
    mount(); fireEvent.click(screen.getByRole('button', { name: '运行仿真' }))
    await screen.findByText('已达到仿真预算，结果不完整')
    expect(screen.getByRole('table', { name: '拟生成指令' })).toHaveTextContent('发放权益')
    expect(screen.getByText('拟生成指令（未执行）')).toBeInTheDocument()
  })
  it.each(['demo', 'permission'])('does not send real requests when %s blocks simulation', (mode) => {
    state.demo = mode === 'demo'; state.allowed = mode !== 'permission'; mount()
    expect(screen.getByRole('button', { name: '运行仿真' })).toBeDisabled(); expect(api.simulateJourney).not.toHaveBeenCalled()
  })
})
