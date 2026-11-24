import { displayTimestamp, displayTimeTick } from '../../components/dateTime'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Area, AreaChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { ArrowUpRight, Download, RefreshCw } from 'lucide-react'
import { Badge, Button, EmptyState, PageHeader, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { Select } from '../../components/Select'
import { RecordTable } from '../../components/RecordTable'
import { DateTimePicker } from '../../components/DateTimePicker'
import { useMutation, useQuery } from '@tanstack/react-query'
import { api } from '../../shared/api/client'
import { problemDetail } from '../../shared/api/problem'
import { MAX_SUBSIDY_RANGE_MS } from '../../shared/api/schemas'
import { useAuth } from '../../shared/auth/useAuth'
import { measurementCoverage } from '../../shared/model/measurementCoverage'
import { displayMeasurementRoi, MEASUREMENT_EVENT_LABELS } from '../../shared/model/measurementMetrics'
import { ANALYTICS_PERIODS, MAX_ANALYTICS_RANGE_MS, analyticsRange, displayMinor, downloadReport, seriesBucketDescription, validateReport, type AnalyticsPeriod, type AnalyticsRange } from './analyticsReport'
import { SubsidyComparisonPanel } from './SubsidyComparisonPanel'

const HOURLY_RANGE_LIMIT_MS = 24 * 60 * 60 * 1000
const chartAmount = new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

export function AnalyticsPage() {
  const auth = useAuth()
  const scope = JSON.stringify([auth.tenantId, auth.subject, [...auth.organizations].sort(), [...auth.shops].sort(), [...auth.permissions].sort()])
  return <AnalyticsWorkspace key={scope} scope={scope} />
}

function AnalyticsWorkspace({ scope }: { scope: string }) {
  const auth = useAuth()
  const [selection, setSelection] = useState<{ period: AnalyticsPeriod | 'CUSTOM'; range: AnalyticsRange }>(() => ({ period: 'TODAY', range: analyticsRange('TODAY') }))
  const [customOpen, setCustomOpen] = useState(false)
  const [params, setParams] = useSearchParams()
  const campaignId = params.get('campaignId') ?? ''
  const [downloadError, setDownloadError] = useState<string>()
  const setCampaignId = (nextId: string) => {
    const next = new URLSearchParams(params)
    if (nextId) next.set('campaignId', nextId)
    else next.delete('campaignId')
    setParams(next)
    setDownloadError(undefined)
  }
  const canRead = !api.demoMode && auth.hasPermission('measurement:read')
  const canListCampaigns = canRead && auth.hasPermission('campaign:read')
  const campaigns = useQuery({ queryKey: ['analytics-campaign-options', scope], queryFn: api.campaigns, enabled: canListCampaigns })
  const campaignAvailable = canListCampaigns && campaigns.isSuccess && campaigns.data.some(campaign => campaign.id === campaignId)
  // 从活动入口进入时先确认当前身份的活动范围，避免回退到不相关的全租户报告。
  const canQuerySelection = canRead && (!campaignId || campaignAvailable)
  const showUnavailableCampaign = canRead && campaignId && (!canListCampaigns || campaigns.isSuccess && !campaignAvailable)
  const { range, period } = selection
  const canReconcile = canQuerySelection && auth.hasPermission('funding:reconcile')
  const withinLedgerWindow = Date.parse(range.to) - Date.parse(range.from) <= MAX_SUBSIDY_RANGE_MS
  const postingParams = new URLSearchParams({ tab: 'funding', postingFrom: range.from, postingTo: range.to })
  if (campaignId) postingParams.set('postingCampaignId', campaignId)
  const report = useQuery({
    queryKey: ['analytics-report', scope, range.from, range.to, range.granularity, campaignId],
    queryFn: async () => {
      const [dashboard, series] = await Promise.all([api.dashboard(range.from, range.to, campaignId || undefined), api.series(range.from, range.to, range.granularity, campaignId || undefined)])
      // 旧服务可能忽略未知query参数，必须确认两份回执都采用同一活动范围。
      if (campaignId && (dashboard.campaignId !== campaignId || series.campaignId !== campaignId)) {
        throw new Error('后端未确认活动筛选范围，请升级测量服务后重试')
      }
      return validateReport({ tenantId: auth.tenantId, campaignId, range, dashboard, points: series.points,
        dayOffset: series.dayOffset, fetchedAt: new Date().toISOString() })
    },
    enabled: canQuerySelection,
    refetchOnWindowFocus: false,
  })
  // 刷新失败、请求未完整返回时不导出缓存的旧数据，避免被误读为当前成功结果。
  const loaded = canQuerySelection && report.isSuccess && !report.isFetching ? report.data : undefined
  const coverage = loaded && measurementCoverage(range.from, range.to, loaded.dashboard.completeThrough)
  const current = coverage === 'complete' ? loaded : undefined
  const dashboard = current?.dashboard
  const watermark = coverage === 'unknown' ? undefined : loaded?.dashboard.completeThrough
  const chart = (current?.points ?? []).map(point => ({ at: point.at, revenue: point.revenueMinor / 100, cost: point.costMinor / 100 }))
  const choose = (next: AnalyticsPeriod) => {
    setCustomOpen(false)
    setDownloadError(undefined)
    setSelection({ period: next, range: analyticsRange(next) })
  }
  const refresh = () => {
    if (period === 'CUSTOM') { setDownloadError(undefined); void report.refetch() }
    else choose(period)
  }
  const recompute = useMutation({ mutationFn: api.recomputeAttribution })
  const canRecompute = !api.demoMode && auth.hasPermission('measurement:ingest')
  const exportCurrent = () => {
    if (!current) return
    try { downloadReport(current); setDownloadError(undefined) } catch (cause) { setDownloadError(problemDetail(cause, '浏览器未能生成报告，请重试')) }
  }

  return <div className="workspace analytics-page">
    <PageHeader title="衡量分析" description="按活动和时间范围查看事件收入、补贴成本及变化趋势。" actions={<>
      <Button disabled={!canQuerySelection || report.isFetching} onClick={refresh}><RefreshCw size={15} />刷新数据</Button>
      <Button tone="primary" disabled={!current} onClick={exportCurrent}><Download size={15} />导出当前报告</Button>
    </>} />
    {api.demoMode ? <StateBanner tone="info" title="请连接演示数据源" detail="此页不内置业务示例。将种子数据写入隔离测试数据库并连接测量接口后，即可读取真实报告；静态演示模式不查询或重算。" /> : !canRead && <StateBanner tone="warn" title="没有查看权限" detail="需要 measurement:read 权限才能查询和导出报告。" />}
    {canListCampaigns && campaigns.isError && <StateBanner tone="error" title="活动选项加载失败" detail={problemDetail(campaigns.error)} />}
    {canListCampaigns && campaigns.isError && <Button onClick={() => void campaigns.refetch()} disabled={campaigns.isFetching}>重试活动选项</Button>}
    {canListCampaigns && campaignId && campaigns.isPending && <StateBanner tone="loading" title="正在确认活动范围" detail="正在读取当前身份可见的活动。" />}
    {showUnavailableCampaign && <>
      <StateBanner tone="warn" title="当前无法查看此活动报告" detail="该活动未出现在当前身份可见列表中。可选择其他可见活动，或打开全部活动报告。" />
      <Button onClick={() => setCampaignId('')}>查看全部活动报告</Button>
    </>}
    {canQuerySelection && report.isFetching && <StateBanner tone="loading" title="正在读取报告" detail="等待汇总和时间序列全部返回后再显示和导出。" />}
    {canQuerySelection && report.isError && <StateBanner tone="error" title="报告加载失败" detail={problemDetail(report.error)} />}
    {downloadError && <StateBanner tone="error" title="报告导出失败" detail={downloadError} />}
    <Panel className="analytics-filter-panel">
      <div className="analytics-filter">
        <div className="field">
          <span>报告时间</span>
          <div className="action-bar analytics-period-controls">
            <div className="segmented" role="group" aria-label="报告时间范围">{Object.entries(ANALYTICS_PERIODS).map(([code, label]) => <button type="button" aria-pressed={period === code} className={period === code ? 'active' : ''} disabled={!canRead} onClick={() => choose(code as AnalyticsPeriod)} key={code}>{label}</button>)}</div>
            <Button disabled={!canRead} aria-expanded={customOpen} onClick={() => setCustomOpen(open => !open)}>自定义时间</Button>
          </div>
        </div>
        <label className="field analytics-campaign-filter"><span>活动筛选</span><Select aria-label="活动筛选" aria-describedby="analytics-campaign-help" value={campaignId} disabled={!canListCampaigns || campaigns.isPending} onChange={event => setCampaignId(event.target.value)}>
          <option value="">全部活动</option>
          {campaignId && !campaignAvailable && <option value={campaignId} disabled>{campaignId} · 待核实</option>}
          {(canListCampaigns && campaigns.isSuccess ? campaigns.data : []).map(campaign => <option key={campaign.id} value={campaign.id}>{campaign.name} · {campaign.id}</option>)}
        </Select></label>
      </div>
      {canRead && customOpen && <CustomAnalyticsRange range={range} apply={next => { setSelection({ period: 'CUSTOM', range: next }); setCustomOpen(false); setDownloadError(undefined) }} />}
      <div className="analytics-query-meta">
        <p className="field-hint">{displayTimestamp(range.from)} 至 {displayTimestamp(range.to)}（不含结束时间）。7 天和 30 天为滚动范围。</p>
        <p className="field-hint" id="analytics-campaign-help">{canListCampaigns ? '按活动 ID 统计。全部活动包含未关联活动的事件。' : '选择活动需要 campaign:read 权限。'}</p>
      </div>
    </Panel>
    {(coverage === 'unknown' || coverage === 'uncovered') && <StateBanner tone="warn" title="数据未就绪" detail={coverage === 'unknown' ? '数据完整时间未知，请稍后刷新。' : `完整数据截至 ${displayTimestamp(watermark)}，尚未覆盖所选范围。`} />}
    {coverage === 'partial' && watermark && <StateBanner tone="warn" title="所选范围的数据尚未齐全" detail={`完整数据截至 ${displayTimestamp(watermark)}。`} action={<Button onClick={() => { setDownloadError(undefined); setSelection({ period, range: { ...range, to: watermark } }) }}>查看已覆盖范围</Button>} />}
    <section className="analytics-metrics">{[
      [MEASUREMENT_EVENT_LABELS.revenue + '（元）', dashboard ? displayMinor(dashboard.attributedRevenueMinor) : '—'],
      [MEASUREMENT_EVENT_LABELS.cost + '（元）', dashboard ? displayMinor(dashboard.fundingCostMinor) : '—'],
      [MEASUREMENT_EVENT_LABELS.roi, dashboard ? displayMeasurementRoi(dashboard) : '—'],
      ['水位延迟（秒）', String(watermark ? loaded?.dashboard.lag?.seconds ?? '—' : '—')],
    ].map(([label, value]) => <Panel key={label}><p>{label}</p><strong>{value}</strong></Panel>)}</section>
    {current && <div className="analytics-grid">
      <Panel className="trend-chart">
        <PanelHeader title="事件收入与事件补贴成本（北京时间）" aside={<Badge tone="info">元</Badge>} />
        {chart.length === 0 ? <EmptyState title="暂无时间序列" detail="当前范围内没有返回数据点，可调整日期范围后重试。" /> : <div className="chart-container">
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={chart} margin={{ top: 8, right: 12, bottom: 4, left: 0 }}>
              <defs><linearGradient id="revenueFillLive" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="var(--teal)" stopOpacity={0.26} /><stop offset="100%" stopColor="var(--teal)" stopOpacity={0.02} /></linearGradient></defs>
              <CartesianGrid stroke="var(--line)" vertical={false} />
              <XAxis dataKey="at" tickFormatter={value => displayTimeTick(value, range.granularity)} interval="preserveStartEnd" minTickGap={24} tick={{ fill: 'var(--ink-600)', fontSize: 'var(--text-xs)' }} axisLine={false} tickLine={false} />
              <YAxis width="auto" tick={{ fill: 'var(--ink-600)', fontSize: 'var(--text-xs)' }} axisLine={false} tickLine={false} />
              <Tooltip labelFormatter={value => displayTimestamp(String(value))} formatter={value => chartAmount.format(Number(value))} />
              <Legend />
              <Area type="monotone" dataKey="revenue" name={MEASUREMENT_EVENT_LABELS.revenue} stroke="var(--teal)" fill="url(#revenueFillLive)" strokeWidth={2} isAnimationActive={false} />
              <Area type="monotone" dataKey="cost" name={MEASUREMENT_EVENT_LABELS.cost} stroke="var(--amber)" fill="transparent" strokeWidth={2} isAnimationActive={false} />
            </AreaChart>
          </ResponsiveContainer>
        </div>}
        <p className="field-hint">{seriesBucketDescription(range.granularity, current.dayOffset)}，窗口首尾可能是部分时段。</p>
      </Panel>
      <Panel><PanelHeader title="事实类型计数" />{Object.keys(current.dashboard.counts).length === 0 ? <EmptyState title="暂无事实计数" detail="当前窗口未返回事实计数。" /> : <RecordTable label="事实类型计数" columns={['事实类型', '净计数']}>
        {Object.entries(current.dashboard.counts).map(([type, count]) => <tr key={type}><td>{type}</td><td>{count}</td></tr>)}
      </RecordTable>}</Panel>
    </div>}
    <div className="analytics-utilities">
      <p className="field-hint">投影完整水位 {displayTimestamp(watermark)}。收入和成本来自事件汇总。</p>
      {current && <p className="field-hint">读取完成 {displayTimestamp(current.fetchedAt)}。</p>}
      <div className="action-bar toolbar">
        {canReconcile && withinLedgerWindow && <Link className="button secondary" to={`/operations?${postingParams}`}>核对补贴流水<ArrowUpRight size={15} aria-hidden="true" /></Link>}
        <Button disabled={!canRecompute || recompute.isPending} title="重算当前租户的归因明细，不受页面日期窗口限制" onClick={() => recompute.mutate()}>{recompute.isPending ? '正在重算…' : '重算全部归因'}</Button>
      </div>
      {recompute.isError && <StateBanner tone="error" title="归因重算失败" detail={problemDetail(recompute.error)} />}
      {recompute.data && <StateBanner tone="success" title="当前租户归因已重算" detail={`转化 ${recompute.data.conversions} 次，贡献 ${recompute.data.credits} 条。完成时间 ${displayTimestamp(recompute.data.completedAt)}。报告仍按事件金额汇总。`} />}
    </div>
    {canReconcile && withinLedgerWindow && <SubsidyComparisonPanel key={JSON.stringify([range.from, range.to, campaignId])}
      scope={scope} tenantId={auth.tenantId} range={range} campaignId={campaignId} eventCost={dashboard?.fundingCostMinor} eventReadAt={current?.fetchedAt} />}
    {canReconcile && !withinLedgerWindow && <p className="field-hint">账本对账窗口最长 31 天，请缩小时间范围后查询。</p>}
  </div>
}

function CustomAnalyticsRange({ range, apply }: { range: AnalyticsRange; apply: (range: AnalyticsRange) => void }) {
  const [from, setFrom] = useState(range.from)
  const [to, setTo] = useState(range.to)
  const span = Date.parse(to) - Date.parse(from)
  const valid = span > 0 && span <= MAX_ANALYTICS_RANGE_MS
  return <form className="form-grid analytics-custom-range" onSubmit={event => {
    event.preventDefault()
    if (valid) apply({ from, to, granularity: span <= HOURLY_RANGE_LIMIT_MS ? 'hour' : 'day' })
  }}>
    <div className="field"><span>报告开始</span><DateTimePicker label="报告开始" value={from} onChange={setFrom} describedBy="analytics-custom-help" /></div>
    <div className="field"><span>报告结束</span><DateTimePicker label="报告结束" value={to} onChange={setTo} describedBy="analytics-custom-help" /></div>
    <p className="field-hint span-2" id="analytics-custom-help">{valid ? '按北京时间选择，包含开始时间，结束时间不计入，最长 366 天。' : '请选择有效范围，结束须晚于开始，且跨度最长 366 天。'}</p>
    <div className="action-bar toolbar span-2"><Button type="submit" tone="primary" disabled={!valid}>查询自定义范围</Button></div>
  </form>
}
