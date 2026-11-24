import { businessDayStart, displayElapsedSeconds, displayTimestamp } from '../../components/dateTime'
import { RecordTable } from '../../components/RecordTable'
import { CircleDollarSign, Megaphone, Sparkles, UsersRound } from 'lucide-react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Badge, DemoBanner, EmptyState, PageHeader, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { problemDetail } from '../../shared/api/problem'
import { api } from '../../shared/api/client'
import { ReleaseState } from '../../shared/api/schemas'
import { useReleasePages } from '../../shared/api/useReleasePages'
import { measurementCoverage } from '../../shared/model/measurementCoverage'
import { displayMeasurementRoi, MEASUREMENT_EVENT_LABELS } from '../../shared/model/measurementMetrics'

const emptyMetrics = [
  { label: '今日' + MEASUREMENT_EVENT_LABELS.revenue, value: '—', delta: '', icon: CircleDollarSign },
  { label: '已加载活动', value: '—', delta: '', icon: Megaphone },
  { label: MEASUREMENT_EVENT_LABELS.cost, value: '—', delta: '', icon: Sparkles },
  { label: '水位延迟', value: '—', delta: '', icon: UsersRound },
] as const

function formatMinor(value: number) {
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(value / 100)
}

export function DashboardPage() {
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
          { label: '今日' + MEASUREMENT_EVENT_LABELS.revenue, value: coveredTotals ? formatMinor(coveredTotals.attributedRevenueMinor) : '—', delta: `完整数据截至 ${displayTimestamp(watermark)}`, icon: CircleDollarSign },
          { label: '已加载活动', value: campaigns.isSuccess ? String(campaigns.data?.length ?? 0) : '—', delta: '包含当前可见范围内的各类活动状态', icon: Megaphone },
          { label: MEASUREMENT_EVENT_LABELS.cost, value: coveredTotals ? formatMinor(coveredTotals.fundingCostMinor) : '—', delta: '', icon: Sparkles },
          { label: '水位延迟', value: displayElapsedSeconds(watermark ? totals.lag?.seconds : undefined), delta: `${watermark && totals.lag ? `接口读取时延迟：${totals.lag.seconds.toLocaleString('zh-CN')} 秒` : '完整时间未知'}${coveredTotals ? `；${MEASUREMENT_EVENT_LABELS.roi} ${displayMeasurementRoi(coveredTotals)}` : ''}`, icon: UsersRound },
        ]
      : emptyMetrics
  const points = coveredTotals && series.isSuccess ? series.data.points : []
  const peak = Math.max(1, ...points.map((point) => point.revenueMinor))

  return <div className="workspace">
    <PageHeader eyebrow="运营总览" title="查看当前运营数据" description={api.demoMode ? '当前为静态预览，连接营销服务后显示运行数据。' : `${campaignSummary}；${releaseSummary}。`} actions={<><Link className="button secondary" to="/releases">查看发布态势</Link><Link className="button primary" to={api.demoMode ? '/campaigns' : '/campaigns?create=true'}><Sparkles size={16} />{api.demoMode ? '查看活动入口' : '创建营销活动'}</Link></>} />
    <DemoBanner />
    {!api.demoMode && campaigns.isError && <StateBanner tone="error" title="活动加载失败" detail={problemDetail(campaigns.error)} />}
    {!api.demoMode && dashboard.isError && <StateBanner tone="error" title="指标加载失败" detail={problemDetail(dashboard.error)} />}
    {!api.demoMode && releases.isError && <StateBanner tone="error" title="发布清单加载失败" detail={problemDetail(releases.error)} />}
    {!api.demoMode && series.isError && <StateBanner tone="error" title="序列加载失败" detail={problemDetail(series.error)} />}
    {coverage && coverage !== 'complete' && <StateBanner tone="warn" title={coverage === 'partial' ? '今日数据尚未齐全' : '今日数据未就绪'} detail={watermark ? `完整数据截至 ${displayTimestamp(watermark)}。` : '数据完整时间未知。'} />}
    <section className="metric-grid" aria-label="核心指标">
      {metrics.map(({ label, value, delta, icon: Icon }) => <article className="metric-card" key={label}><div className="metric-icon"><Icon size={18} /></div><p>{label}</p><strong>{value}</strong><span>{delta}</span></article>)}
    </section>
    <section className="dashboard-grid">
      <Panel className="pulse-panel"><PanelHeader eyebrow="经营数据" title="小时事件收入" aside={<Badge tone={points.length ? 'good' : 'info'}>{points.length ? 'API' : '暂无序列'}</Badge>} />
        {api.demoMode
          ? <EmptyState title="收入序列需要连接服务" detail="静态预览不展示经营数据；连接后按事件收入绘制。" />
          : coverage && !coveredTotals
            ? <EmptyState title="今日小时数据尚未齐全" detail="在衡量分析中可查询已覆盖的范围。" action={<Link className="button secondary" to="/analytics">打开衡量分析</Link>} />
          : points.length === 0
            ? <EmptyState title="暂无小时序列" detail="GET /api/v1/measurements/series 尚未返回数据点。" />
            : points.some((point) => point.revenueMinor < 0)
              ? <RecordTable label="小时事件收入明细" columns={['时间（北京时间）', MEASUREMENT_EVENT_LABELS.revenue]}>
                {points.slice(-12).map((point) => <tr key={point.at}><td>{displayTimestamp(point.at)}</td><td>{formatMinor(point.revenueMinor)}</td></tr>)}
              </RecordTable>
            : <div className="pulse-chart" aria-label="今日小时事件收入">{points.slice(-12).map((point) => <span key={point.at} style={{ '--height': `${Math.round((point.revenueMinor / peak) * 100)}%` } as React.CSSProperties} title={`${displayTimestamp(point.at)} · ${formatMinor(point.revenueMinor)}`} />)}</div>}
      </Panel>
      <Panel className="readiness-panel"><PanelHeader eyebrow="发布就绪" title="最近发布清单" aside={<Link className="text-button" to="/releases">打开控制台 →</Link>} />
        {api.demoMode ? <EmptyState title="发布状态需要连接服务" detail="连接后读取实际发布清单及副本状态；静态预览不代表部署就绪。" /> : <RecordTable label="Cell 就绪度" columns={['发布清单 ID', '部署单元', '状态', '就绪副本', '声明容量']}>{releases.items.slice(0, 3).map((item) => <tr key={item.manifest.manifestId}><td>{item.manifest.manifestId}</td><td>{item.manifest.cell}</td><td><Badge tone={item.state === ReleaseState.ACTIVE ? 'good' : 'warn'}>{item.state}</Badge></td><td>{item.readyReplicas}</td><td>{item.readyCapacity}</td></tr>)}</RecordTable>}
      </Panel>
    </section>
  </div>
}
