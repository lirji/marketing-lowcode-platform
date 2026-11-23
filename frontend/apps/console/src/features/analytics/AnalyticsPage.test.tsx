import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthStateContext, devAuthState, type AuthState } from '../../shared/auth/auth-context'
import type { DashboardMetrics } from '../../shared/api/schemas'

const mocks = vi.hoisted(() => ({ campaigns: vi.fn(), dashboard: vi.fn(), series: vi.fn(), recompute: vi.fn(), download: vi.fn(), demo: false }))
vi.mock('../../shared/api/client', () => ({ api: { get demoMode() { return mocks.demo }, campaigns: mocks.campaigns, dashboard: mocks.dashboard, series: mocks.series, recomputeAttribution: mocks.recompute } }))
vi.mock('./analyticsReport', async importOriginal => ({ ...await importOriginal<typeof import('./analyticsReport')>(), downloadReport: mocks.download }))
import { AnalyticsPage } from './AnalyticsPage'

const dashboard: DashboardMetrics = { counts: { CONVERSION_CONFIRMED: 3 }, attributedRevenueMinor: 12345, fundingCostMinor: 67, roi: '183.2537', completeThrough: new Date(Date.now() + 60_000).toISOString(), lag: { seconds: 12 } }
function fixture() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const tree = (identity: AuthState) => <QueryClientProvider client={client}><AuthStateContext.Provider value={identity}><MemoryRouter><AnalyticsPage /></MemoryRouter></AuthStateContext.Provider></QueryClientProvider>
  const view = render(tree(devAuthState))
  return { ...view, switchIdentity: (identity: AuthState) => view.rerender(tree(identity)) }
}
const deferred = <T,>() => { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }

beforeEach(() => {
  mocks.campaigns.mockReset().mockResolvedValue([{ id: 'cmp-a', name: '活动甲' }, { id: 'cmp-b', name: '活动乙' }])
  mocks.demo = false
  mocks.dashboard.mockReset().mockResolvedValue(dashboard)
  mocks.series.mockReset().mockResolvedValue({ points: [] })
  mocks.recompute.mockReset().mockResolvedValue({ conversions: 3, credits: 4, completedAt: '2026-11-17T00:00:00Z' })
  mocks.download.mockReset()
})

describe('AnalyticsPage', () => {
  it('waits for both responses, shares one range and exports the displayed report without querying again', async () => {
    const series = deferred<{ points: [] }>()
    mocks.series.mockReturnValueOnce(series.promise)
    fixture()
    await waitFor(() => expect(mocks.dashboard).toHaveBeenCalledTimes(1))
    expect(screen.getByRole('button', { name: '导出当前报告' })).toBeDisabled()
    expect(screen.queryByText('123.45')).not.toBeInTheDocument()
    await act(async () => series.resolve({ points: [] }))
    await expectReady()
    expect(mocks.dashboard.mock.calls[0].slice(0, 2)).toEqual(mocks.series.mock.calls[0].slice(0, 2))
    await userEvent.click(screen.getByRole('button', { name: '导出当前报告' }))
    expect(mocks.download.mock.calls[0][0].dashboard.attributedRevenueMinor).toBe(12345)
    expect(mocks.dashboard).toHaveBeenCalledTimes(1)
    expect(screen.getByText('0.67')).toBeInTheDocument()
    expect(screen.queryByText('双11家电主会场')).not.toBeInTheDocument()
  })
  it('shows unknown coverage instead of zero, and exports a freshly queried covered window', async () => {
    mocks.dashboard.mockResolvedValue({ ...dashboard, attributedRevenueMinor: 0, fundingCostMinor: 0, completeThrough: '1970-01-01T00:00:00Z' })
    fixture()
    expect(await screen.findByText('数据未就绪')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '导出当前报告' })).toBeDisabled()
    expect(screen.queryByText('0.00')).not.toBeInTheDocument()
    const through = new Date(Date.now() - 60_000).toISOString()
    mocks.dashboard.mockResolvedValue({ ...dashboard, attributedRevenueMinor: 0, fundingCostMinor: 0, completeThrough: through })
    await userEvent.click(screen.getByRole('button', { name: '7 天' }))
    expect(await screen.findByText('所选范围的数据尚未齐全')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '导出当前报告' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: '查看已覆盖范围' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '导出当前报告' })).toBeEnabled())
    expect(screen.getAllByText('0.00')).toHaveLength(2)
    expect(screen.getByText('不适用')).toBeInTheDocument()
    expect(mocks.dashboard.mock.calls.at(-1)?.[1]).toBe(through)
    expect(mocks.series.mock.calls.at(-1)?.[1]).toBe(through)
  })
  it('switches range while old requests are pending and ignores the late previous report', async () => {
    const old = deferred<DashboardMetrics>()
    mocks.dashboard.mockReturnValueOnce(old.promise)
    fixture()
    await waitFor(() => expect(mocks.dashboard).toHaveBeenCalledTimes(1))
    await userEvent.click(screen.getByRole('button', { name: '7 天' }))
    await expectReady()
    expect(mocks.series.mock.calls.at(-1)?.[2]).toBe('day')
    await act(async () => old.resolve({ ...dashboard, attributedRevenueMinor: 99999 }))
    expect(screen.queryByText('999.99')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '导出当前报告' }))
    expect(mocks.download.mock.calls[0][0].range.granularity).toBe('day')
  })
  it('does not export partial or failed refreshes and allows retry without changing the period', async () => {
    fixture(); await expectReady()
    mocks.series.mockRejectedValueOnce(new Error('测量服务不可用'))
    await userEvent.click(screen.getByRole('button', { name: '刷新数据' }))
    expect(await screen.findByText('报告加载失败')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '导出当前报告' })).toBeDisabled()
    expect(screen.queryByText('123.45')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '刷新数据' }))
    await expectReady()
  })
  it('clears the previous tenant report and blocks export while the next tenant is loading', async () => {
    const view = fixture(); await expectReady()
    const next = deferred<DashboardMetrics>()
    mocks.dashboard.mockReturnValueOnce(next.promise)
    view.switchIdentity({ ...devAuthState, tenantId: 'other' })
    await waitFor(() => expect(mocks.dashboard).toHaveBeenCalledTimes(2))
    expect(screen.queryByText('123.45')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '导出当前报告' })).toBeDisabled()
    await act(async () => next.resolve({ ...dashboard, attributedRevenueMinor: 500 }))
    expect(await screen.findByText('5.00')).toBeInTheDocument()
  })
  it('does not query without read permission or expose cached values', async () => {
    const view = fixture(); await expectReady()
    view.switchIdentity({ ...devAuthState, permissions: [], hasPermission: () => false })
    expect(screen.getByText('没有查看权限')).toBeInTheDocument()
    expect(screen.queryByText('123.45')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '导出当前报告' })).toBeDisabled()
    expect(mocks.dashboard).toHaveBeenCalledTimes(1)
  })
  it('does not invent DEMO revenue, experiments, or query a live endpoint', () => {
    mocks.demo = true; fixture()
    expect(screen.getByText('请连接演示数据源')).toBeInTheDocument()
    expect(screen.queryByText('¥ 18.43M')).not.toBeInTheDocument()
    expect(screen.queryByText('SRM PASS')).not.toBeInTheDocument()
    expect(mocks.dashboard).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '重算全部归因' })).toBeDisabled()
  })
  it('filters both requests by the chosen campaign and exports the confirmed scope', async () => {
    fixture(); await expectReady()
    mocks.dashboard.mockResolvedValue({ ...dashboard, campaignId: 'cmp-a', attributedRevenueMinor: 100 })
    mocks.series.mockResolvedValue({ points: [], campaignId: 'cmp-a' })
    await userEvent.click(screen.getByRole('combobox', { name: '活动筛选' }))
    await userEvent.click(screen.getByRole('option', { name: '活动甲 · cmp-a' }))
    expect(await screen.findByText('1.00')).toBeInTheDocument()
    expect(mocks.dashboard.mock.calls.at(-1)?.[2]).toBe('cmp-a')
    expect(mocks.series.mock.calls.at(-1)?.[3]).toBe('cmp-a')
    await userEvent.click(screen.getByRole('button', { name: '导出当前报告' }))
    expect(mocks.download.mock.calls[0][0].campaignId).toBe('cmp-a')
  })
  it('refuses an old server that ignores the filter and recovers by choosing all campaigns', async () => {
    fixture(); await expectReady()
    await userEvent.click(screen.getByRole('combobox', { name: '活动筛选' }))
    await userEvent.click(screen.getByRole('option', { name: '活动甲 · cmp-a' }))
    expect(await screen.findByText('后端未确认活动筛选范围，请升级测量服务后重试')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '导出当前报告' })).toBeDisabled()
    expect(screen.queryByText('123.45')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('combobox', { name: '活动筛选' }))
    await userEvent.click(screen.getByRole('option', { name: '全部活动' }))
    await expectReady()
  })
  it('keeps tenant summary readable without campaign-list permission', async () => {
    const view = fixture(); await expectReady()
    view.switchIdentity({ ...devAuthState, permissions: ['measurement:read'], hasPermission: permission => permission === 'measurement:read' })
    await expectReady()
    expect(screen.getByRole('combobox', { name: '活动筛选' })).toBeDisabled()
    expect(mocks.campaigns).toHaveBeenCalledTimes(1)
  })
  it('labels recompute as tenant-wide and refreshes the report after success', async () => {
    fixture(); await expectReady()
    const run = screen.getByRole('button', { name: '重算全部归因' })
    expect(run).toHaveAttribute('title', expect.stringContaining('不受页面日期窗口限制'))
    await userEvent.click(run)
    expect(await screen.findByText('当前租户归因已重算')).toBeInTheDocument()
    await waitFor(() => expect(mocks.dashboard).toHaveBeenCalledTimes(2))
  })
})
async function expectReady() {
  await waitFor(() => expect(screen.getByRole('button', { name: '导出当前报告' })).toBeEnabled())
  expect(screen.getByText('123.45')).toBeInTheDocument()
}
