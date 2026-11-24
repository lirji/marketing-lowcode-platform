import { businessDayStart, displayElapsedSeconds, displayTimeTick, displayTimestamp } from '../../components/dateTime'
import { RecordTable } from '../../components/RecordTable'
import { ArrowUpRight, CircleDollarSign, Clock3, Megaphone, Plus, Receipt } from 'lucide-react'
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Badge, DemoBanner, EmptyState, PageHeader, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { problemDetail } from '../../shared/api/problem'
import { api } from '../../shared/api/client'
import { ReleaseState } from '../../shared/api/schemas'
import { useReleasePages } from '../../shared/api/useReleasePages'
import { measurementCoverage } from '../../shared/model/measurementCoverage'
import { displayMeasurementRoi, MEASUREMENT_EVENT_LABELS } from '../../shared/model/measurementMetrics'
import { useAuth } from '../../shared/auth/useAuth'

const emptyMetrics = [
  { label: '今日' + MEASUREMENT_EVENT_LABELS.revenue, value: '—', delta: '', icon: CircleDollarSign },
  { label: '已加载活动', value: '—', delta: '', icon: Megaphone },
  { label: MEASUREMENT_EVENT_LABELS.cost, value: '—', delta: '', icon: Receipt },
  { label: '水位延迟', value: '—', delta: '', icon: Clock3 },
] as const

const releaseStatus: Partial<Record<string, { label: string; tone: 'warn' | 'good' | 'info' | 'neutral' }>> = {
  [ReleaseState.STAGED]: { label: '已暂存', tone: 'warn' },
  [ReleaseState.ACTIVE]: { label: '已激活', tone: 'good' },
  [ReleaseState.ROLLED_BACK]: { label: '已回滚', tone: 'info' },
  [ReleaseState.REVOKED]: { label: '已撤销', tone: 'neutral' },
} as const

const releaseColumns = [
  { label: '发布清单 ID', width: 184 }, { label: '状态', width: 84 }, { label: '部署单元', width: 92 },
  { label: '就绪副本', width: 100 }, { label: '声明容量', width: 100 },
] as const

function formatMinor(value: number) {
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(value / 100)
}

export function DashboardPage() {
  const auth = useAuth()
  const dashboard = useQuery({
    queryKey: ['dashboard'],
    queryFn: async () => {
      const now = new Date()
      const from = businessDayStart(now).toISOString()
      const to = now.toISOString()
      const totals = await api.dashboard(from, to)
      const coverage = measurementCoverage(from, to, totals.completeThrough)
      if (coverage === 'complete') return { from, totals, coverage, coveredTo: to, coveredTotals: totals }
      if (coverage === 'partial' && totals.completeThrough) {
        // 缩短查询窗口，避免把水位之后尚未齐全的事件算进当天指标。
        const coveredTo = totals.completeThrough
        const coveredTotals = await api.dashboard(from, coveredTo)
        if (measurementCoverage(from, coveredTo, coveredTotals.completeThrough) === 'complete') {
          return { from, totals, coverage, coveredTo, coveredTotals }
        }
      }
      return { from, totals, coverage, coveredTo: undefined, coveredTotals: undefined }
    },
    enabled: !api.demoMode,
  })
  const window = dashboard.isSuccess && !dashboard.isFetching ? dashboard.data : undefined
  const totals = window?.totals
  const coverage = window?.coverage
  const coveredTotals = window?.coveredTotals
  const watermark = coverage === 'unknown' ? undefined : totals?.completeThrough
  const series = useQuery({
    queryKey: ['series', 'hour', 'today', window?.from, window?.coveredTo],
    queryFn: () => api.series(window!.from, window!.coveredTo!, 'hour'),
    enabled: !api.demoMode && Boolean(window?.coveredTo),
  })
  const campaigns = useQuery({ queryKey: ['campaigns'], queryFn: api.campaigns, enabled: !api.demoMode })
  const releases = useReleasePages()
  const campaignSummary = campaigns.isError ? '活动读取失败' : campaigns.isPending ? '活动加载中' : `当前已加载 ${campaigns.data?.length ?? 0} 个活动`
  const releaseSummary = !releases.canRead ? '无发布读取权限'
    : releases.isError ? '发布清单读取失败'
      : releases.isPending ? '发布清单加载中'
        : `已加载 ${releases.items.length} 条发布清单${releases.hasNextPage ? '，还有更多可在发布中心查看' : ''}`
  const metrics = !api.demoMode && totals
      ? [
          { label: '今日' + MEASUREMENT_EVENT_LABELS.revenue, value: coveredTotals ? formatMinor(coveredTotals.attributedRevenueMinor) : '—', delta: coverage === 'complete' ? `完整数据截至 ${displayTimestamp(watermark)}` : coveredTotals ? '按已覆盖时段汇总' : '', icon: CircleDollarSign },
          { label: '已加载活动', value: campaigns.isSuccess ? String(campaigns.data?.length ?? 0) : '—', delta: '', icon: Megaphone },
          { label: MEASUREMENT_EVENT_LABELS.cost, value: coveredTotals ? formatMinor(coveredTotals.fundingCostMinor) : '—', delta: coveredTotals ? `${MEASUREMENT_EVENT_LABELS.roi} ${displayMeasurementRoi(coveredTotals)}` : '', icon: Receipt },
          { label: '水位延迟', value: displayElapsedSeconds(watermark ? totals.lag?.seconds : undefined), delta: watermark && totals.lag ? `接口读取时 ${totals.lag.seconds.toLocaleString('zh-CN')} 秒` : '完整时间未知', icon: Clock3 },
        ]
      : emptyMetrics
  const points = coveredTotals && series.isSuccess ? series.data.points : []
  const canOpenAnalysis = auth.hasPermission('measurement:read')
  const canCreate = auth.hasPermission('campaign:read') && auth.hasPermission('campaign:write')

  return <div className="workspace dashboard-page">
    <PageHeader title="查看当前运营数据" description={api.demoMode ? '当前为静态预览，连接营销服务后显示运行数据。' : `${campaignSummary}，${releaseSummary}。`} actions={<>
      {(api.demoMode || releases.canRead) && <Link className="button secondary" to="/releases">查看发布态势</Link>}
      {(api.demoMode || canCreate) && <Link className="button primary" to={api.demoMode ? '/campaigns' : '/campaigns?create=true'}><Plus size={17} aria-hidden="true" />{api.demoMode ? '查看活动入口' : '创建营销活动'}</Link>}
    </>} />
    <DemoBanner />
    {!api.demoMode && campaigns.isError && <StateBanner tone="error" title="活动加载失败" detail={problemDetail(campaigns.error)} />}
    {!api.demoMode && dashboard.isError && <StateBanner tone="error" title="指标加载失败" detail={problemDetail(dashboard.error)} />}
    {!api.demoMode && releases.isError && <StateBanner tone="error" title="发布清单加载失败" detail={problemDetail(releases.error)} />}
    {!api.demoMode && series.isError && <StateBanner tone="error" title="序列加载失败" detail={problemDetail(series.error)} />}
    {coverage && coverage !== 'complete' && <StateBanner tone={coveredTotals ? 'info' : 'warn'} title={coverage === 'partial' ? '今日数据尚未齐全' : '今日数据未就绪'} detail={watermark ? `完整数据截至 ${displayTimestamp(watermark)}。` : '数据完整时间未知。'} />}
    <section className="metric-grid" aria-label="核心指标">
      {metrics.map(({ label, value, delta, icon: Icon }, index) => <article className={`metric-card${index === 0 ? ' metric-emphasis' : ''}`} key={label}>
        <div className="metric-label"><p>{label}</p><span className="metric-icon"><Icon size={17} aria-hidden="true" /></span></div>
        <strong>{value}</strong>
        {delta && <span>{delta}</span>}
        {index === 1 && !api.demoMode && auth.hasPermission('campaign:read') && <Link to="/campaigns" className="metric-link">查看活动<ArrowUpRight size={13} aria-hidden="true" /></Link>}
      </article>)}
    </section>
    <section className="dashboard-grid">
      <Panel className="pulse-panel"><PanelHeader title="小时事件收入" aside={canOpenAnalysis && <Link className="text-button section-link" to="/analytics">衡量分析<ArrowUpRight size={15} aria-hidden="true" /></Link>} />
        {api.demoMode
          ? <EmptyState title="收入序列需要连接服务" detail="静态预览不展示经营数据；连接后按事件收入绘制。" />
          : coverage && !coveredTotals
            ? <EmptyState title="今日小时数据尚未齐全" detail="有完整时段后会在这里显示趋势。" />
          : points.length === 0
            ? <EmptyState title="暂无小时序列" detail="GET /api/v1/measurements/series 尚未返回数据点。" />
            : points.some((point) => point.revenueMinor < 0)
              ? <RecordTable label="小时事件收入明细" columns={['时间（北京时间）', MEASUREMENT_EVENT_LABELS.revenue]}>
                {points.map((point) => <tr key={point.at}><td>{displayTimestamp(point.at)}</td><td>{formatMinor(point.revenueMinor)}</td></tr>)}
              </RecordTable>
            : <div className="pulse-chart" aria-label="今日小时事件收入">
              <p className="chart-unit">事件收入（元）</p>
              <div className="chart-container">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={points} margin={{ top: 8, right: 12, bottom: 4, left: 0 }}>
                    <CartesianGrid vertical={false} stroke="var(--line)" />
                    <XAxis dataKey="at" tickFormatter={value => displayTimeTick(value, 'hour')} interval="preserveStartEnd" minTickGap={20} tickLine={false} tick={{ fill: 'var(--ink-600)', fontSize: 'var(--text-xs)' }} />
                    <YAxis width="auto" tickFormatter={value => (Number(value) / 100).toLocaleString('zh-CN')} tickLine={false} axisLine={false} tick={{ fill: 'var(--ink-600)', fontSize: 'var(--text-xs)' }} />
                    <Tooltip labelFormatter={value => displayTimestamp(String(value))} formatter={value => formatMinor(Number(value))} cursor={{ fill: 'var(--canvas)' }} />
                    <Bar dataKey="revenueMinor" name="事件收入" fill="var(--teal)" maxBarSize={42} radius={[5, 5, 0, 0]} isAnimationActive={false} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
              <p className="chart-time">北京时间</p>
            </div>}
      </Panel>
      <Panel className="readiness-panel"><PanelHeader title="最近发布清单" aside={(api.demoMode || releases.canRead) && <Link className="text-button section-link" to="/releases">发布中心<ArrowUpRight size={15} aria-hidden="true" /></Link>} />
        {api.demoMode ? <EmptyState title="发布状态需要连接服务" detail="连接后读取实际发布清单及副本状态。" /> : releases.items.length ? <RecordTable label="Cell 就绪度" columns={releaseColumns}>
          {releases.items.slice(0, 3).map((item) => {
            const status = releaseStatus[item.state]
            return <tr key={item.manifest.manifestId}>
              <td>{item.manifest.manifestId}</td>
              <td><Badge tone={status?.tone}>{status?.label ?? item.state}</Badge></td>
              <td>{item.manifest.cell}</td>
              <td>{item.readyReplicas}</td><td>{item.readyCapacity}</td>
            </tr>
          })}
        </RecordTable> : <p className="panel-result">{releases.canRead && releases.isSuccess ? '暂无发布清单' : releaseSummary}</p>}
      </Panel>
    </section>
  </div>
}
