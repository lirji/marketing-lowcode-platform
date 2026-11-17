import { displayTimestamp, displayTimeTick } from '../../components/dateTime'
import { useState } from 'react'
import { Area, AreaChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Download, RefreshCw } from 'lucide-react'
import { Badge, Button, EmptyState, PageHeader, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { Select } from '../../components/Select'
import { RecordTable } from '../../components/RecordTable'
import { useMutation, useQuery } from '@tanstack/react-query'
import { api } from '../../shared/api/client'
import { problemDetail } from '../../shared/api/problem'
import { useAuth } from '../../shared/auth/useAuth'
import { ANALYTICS_PERIODS, analyticsRange, displayMinor, downloadReport, validateReport, type AnalyticsPeriod } from './analyticsReport'

export function AnalyticsPage() {
  const auth = useAuth()
  const scope = JSON.stringify([auth.tenantId, auth.subject, [...auth.organizations].sort(), [...auth.shops].sort(), [...auth.permissions].sort()])
  return <AnalyticsWorkspace key={scope} scope={scope} />
}

function AnalyticsWorkspace({ scope }: { scope: string }) {
  const auth = useAuth()
  const [selection, setSelection] = useState(() => ({ period: 'TODAY' as AnalyticsPeriod, range: analyticsRange('TODAY') }))
  const [campaignId, setCampaignId] = useState('')
  const [downloadError, setDownloadError] = useState<string>()
  const canRead = !api.demoMode && auth.hasPermission('measurement:read')
  const canListCampaigns = canRead && auth.hasPermission('campaign:read')
  const campaigns = useQuery({ queryKey: ['analytics-campaign-options', scope], queryFn: api.campaigns, enabled: canListCampaigns })
  const { range, period } = selection
  const report = useQuery({
    queryKey: ['analytics-report', scope, range.from, range.to, range.granularity, campaignId],
    queryFn: async () => {
      const [dashboard, series] = await Promise.all([api.dashboard(range.from, range.to, campaignId || undefined), api.series(range.from, range.to, range.granularity, campaignId || undefined)])
      // 旧服务可能忽略未知query参数，必须确认两份回执都采用同一活动范围。
      if (campaignId && (dashboard.campaignId !== campaignId || series.campaignId !== campaignId)) {
        throw new Error('后端未确认活动筛选范围，请升级测量服务后重试')
      }
      return validateReport({ tenantId: auth.tenantId, campaignId, range, dashboard, points: series.points, fetchedAt: new Date().toISOString() })
    },
    enabled: canRead,
    refetchOnWindowFocus: false,
  })
  // 刷新失败、请求未完整返回时不导出缓存的旧数据，避免被误读为当前成功结果。
  const current = canRead && report.isSuccess && !report.isFetching ? report.data : undefined
  const dashboard = current?.dashboard
  const chart = (current?.points ?? []).map(point => ({ time: displayTimeTick(point.at, range.granularity), revenue: point.revenueMinor / 100, cost: point.costMinor / 100 }))
  const choose = (next: AnalyticsPeriod) => { setDownloadError(undefined); setSelection({ period: next, range: analyticsRange(next) }) }
  const recompute = useMutation({ mutationFn: api.recomputeAttribution, onSuccess: () => setSelection(current => ({ period: current.period, range: analyticsRange(current.period) })) })
  const canRecompute = !api.demoMode && auth.hasPermission('measurement:ingest')
  const exportCurrent = () => {
    if (!current) return
    try { downloadReport(current); setDownloadError(undefined) } catch (cause) { setDownloadError(problemDetail(cause, '浏览器未能生成报告，请重试')) }
  }

  return <div className="workspace analytics-page">
    <PageHeader eyebrow="衡量分析" title="衡量分析" description="查看当前租户的投影汇总与时间序列；范围和数据水位随报告一起导出。" actions={<>
      <Button disabled={!canRead || report.isFetching} onClick={() => choose(period)}><RefreshCw size={15} />刷新数据</Button>
      <Button disabled={!current} onClick={exportCurrent}><Download size={15} />导出当前报告</Button>
      <Button disabled={!canRecompute || recompute.isPending} title="重算当前租户全部归因，不受页面日期窗口限制" onClick={() => recompute.mutate()}>{recompute.isPending ? '正在重算…' : '重算全部归因'}</Button>
    </>} />
    {api.demoMode ? <StateBanner tone="info" title="请连接演示数据源" detail="此页不内置业务示例。将种子数据写入隔离测试数据库并连接测量接口后，即可读取真实报告；静态演示模式不查询或重算。" /> : !canRead && <StateBanner tone="warn" title="没有查看权限" detail="需要 measurement:read 权限才能查询和导出报告。" />}
    {canListCampaigns && campaigns.isError && <StateBanner tone="error" title="活动选项加载失败" detail={problemDetail(campaigns.error)} />}
    {canListCampaigns && campaigns.isError && <Button onClick={() => void campaigns.refetch()} disabled={campaigns.isFetching}>重试活动选项</Button>}
    {canRead && report.isFetching && <StateBanner tone="loading" title="正在读取报告" detail="等待汇总和时间序列全部返回后再显示和导出。" />}
    {canRead && report.isError && <StateBanner tone="error" title="报告加载失败" detail={problemDetail(report.error)} />}
    {downloadError && <StateBanner tone="error" title="报告导出失败" detail={downloadError} />}
    {recompute.isError && <StateBanner tone="error" title="归因重算失败" detail={problemDetail(recompute.error)} />}
    {recompute.data && <StateBanner tone="success" title="当前租户归因已重算" detail={`转化 ${recompute.data.conversions} 次，贡献 ${recompute.data.credits} 条；完成时间：${displayTimestamp(recompute.data.completedAt)}`} />}
    <div className="analytics-filter"><div className="segmented" aria-label="报告时间范围">{Object.entries(ANALYTICS_PERIODS).map(([code, label]) => <button type="button" aria-pressed={period === code} className={period === code ? 'active' : ''} disabled={!canRead} onClick={() => choose(code as AnalyticsPeriod)} key={code}>{label}</button>)}</div><label className="field analytics-campaign-filter"><span>活动筛选</span><Select aria-label="活动筛选" aria-describedby="analytics-campaign-help" value={campaignId} disabled={!canListCampaigns || campaigns.isPending} onChange={event => { setCampaignId(event.target.value); setDownloadError(undefined) }}>
      <option value="">全部活动</option>{(campaigns.data ?? []).map(campaign => <option key={campaign.id} value={campaign.id}>{campaign.name} · {campaign.id}</option>)}
    </Select></label><Badge tone="info">{range.granularity === 'hour' ? '小时序列' : '日序列'}</Badge></div>
    <p className="field-hint" id="analytics-campaign-help">{canListCampaigns ? '按所选活动ID匹配投影数据；无匹配返回零值。全部活动包含未关联活动的事实。' : '选择活动需要 campaign:read 权限；全租户统计仍由 measurement:read 控制。'}</p>
    <p className="field-hint">查询范围：{displayTimestamp(range.from)} 至 {displayTimestamp(range.to)}（不含结束时间）。7天和30天为滚动窗口。当前范围：{campaignId || '全部活动'}；尚不支持归因策略筛选。</p>
    <p className="field-hint">{range.granularity === 'day' ? '日序列按后端UTC自然日分桶，显示北京时间；首尾可能是不完整桶。' : '时间序列按小时分桶，显示北京时间。'}汇总与序列采用相同窗口，读取并非数据库原子快照。</p>
    <p className="field-hint">读取完成：{displayTimestamp(current?.fetchedAt)}。重算全部归因会处理当前租户全部转化，不受页面日期范围限制。</p>
    {dashboard?.completeThrough && Date.parse(dashboard.completeThrough) < Date.parse(range.from) && <StateBanner tone="warn" title="当前水位尚未覆盖所选窗口" detail="以下数值是当前已读取的投影结果，不能据此认定整个窗口内没有业务发生；请等待数据推进后刷新。" />}
    <section className="analytics-metrics">{[
      ['归因收入（元）', dashboard ? displayMinor(dashboard.attributedRevenueMinor) : '—'],
      ['补贴成本（元）', dashboard ? displayMinor(dashboard.fundingCostMinor) : '—'],
      ['ROI', String(dashboard?.roi ?? '—')],
      ['水位延迟（秒）', String(dashboard?.lag?.seconds ?? '—')],
    ].map(([label, value]) => <Panel key={label}><p>{label}</p><strong>{value}</strong></Panel>)}</section>
    <p className="field-hint">投影完整水位：{displayTimestamp(dashboard?.completeThrough)}。CSV包含接口原始分值、完整返回序列和范围信息。</p>
    {current && <div className="analytics-grid">
      <Panel className="trend-chart"><PanelHeader eyebrow="REVENUE / COST" title="归因收入与补贴成本（北京时间）" aside={<Badge tone="info">元</Badge>} />{chart.length === 0 ? <EmptyState title="暂无时间序列" detail="当前范围内没有返回数据点，可调整日期范围后重试。" /> : <div className="chart-container"><ResponsiveContainer width="100%" height="100%"><AreaChart data={chart}><defs><linearGradient id="revenueFillLive" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="#168b80" stopOpacity={0.35}/><stop offset="100%" stopColor="#168b80" stopOpacity={0.02}/></linearGradient></defs><CartesianGrid stroke="#e9eeee" vertical={false}/><XAxis dataKey="time" tick={{ fontSize: 'var(--text-xs)' }} axisLine={false}/><YAxis tick={{ fontSize: 'var(--text-xs)' }} axisLine={false}/><Tooltip labelFormatter={label => `${label}（北京时间）`} /><Legend/><Area type="monotone" dataKey="revenue" name="归因收入" stroke="#168b80" fill="url(#revenueFillLive)" strokeWidth={2}/><Area type="monotone" dataKey="cost" name="补贴成本" stroke="#d18a27" fill="transparent" strokeWidth={2}/></AreaChart></ResponsiveContainer></div>}</Panel>
      <Panel><PanelHeader eyebrow="COUNTS" title="事实类型计数" />{Object.keys(current.dashboard.counts).length === 0 ? <EmptyState title="暂无事实计数" detail="当前窗口未返回事实计数。" /> : <RecordTable label="事实类型计数" columns={['事实类型', '净计数']}>
        {Object.entries(current.dashboard.counts).map(([type, count]) => <tr key={type}><td>{type}</td><td>{count}</td></tr>)}
      </RecordTable>}</Panel>
    </div>}
  </div>
}
