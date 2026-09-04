import { CircleDollarSign, Megaphone, Sparkles, UsersRound } from 'lucide-react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Badge, DemoBanner, EmptyState, PageHeader, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { problemDetail } from '../../shared/api/problem'
import { api } from '../../shared/api/client'

const demoMetrics = [
  { label: '今日归因成交', value: '¥ 18,426,780', delta: '+12.8%', icon: CircleDollarSign },
  { label: '在线活动', value: '126', delta: '4 待审批', icon: Megaphone },
  { label: '实时触达 / min', value: '84,291', delta: '99.96% 成功', icon: Sparkles },
  { label: '覆盖会员', value: '31.8M', delta: '水位 12s', icon: UsersRound },
] as const

const emptyMetrics = [
  { label: '今日归因成交', value: '—', delta: '', icon: CircleDollarSign },
  { label: '在线活动', value: '—', delta: '', icon: Megaphone },
  { label: '营销成本', value: '—', delta: '', icon: Sparkles },
  { label: '水位延迟', value: '—', delta: '', icon: UsersRound },
] as const

function formatMinor(value: number) {
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 0 }).format(value / 100)
}

function startOfToday() {
  const from = new Date()
  from.setHours(0, 0, 0, 0)
  return from
}

export function DashboardPage() {
  const from = startOfToday()
  const to = new Date()
  const dashboard = useQuery({
    queryKey: ['dashboard'],
    queryFn: () => api.dashboard(from.toISOString(), to.toISOString()),
    enabled: !api.demoMode,
  })
  const series = useQuery({
    queryKey: ['series', 'hour', 'today'],
    queryFn: () => api.series(from.toISOString(), to.toISOString(), 'hour'),
    enabled: !api.demoMode,
  })
  const campaigns = useQuery({ queryKey: ['campaigns'], queryFn: api.campaigns, enabled: !api.demoMode })
  const releases = useQuery({ queryKey: ['releases'], queryFn: api.releases, enabled: !api.demoMode })
  const metrics = api.demoMode
    ? demoMetrics
    : dashboard.data
      ? [
          { label: '今日归因成交', value: formatMinor(dashboard.data.attributedRevenueMinor), delta: dashboard.data.completeThrough ?? '', icon: CircleDollarSign },
          { label: '在线活动', value: String(campaigns.data?.length ?? 0), delta: '', icon: Megaphone },
          { label: '营销成本', value: formatMinor(dashboard.data.fundingCostMinor), delta: '', icon: Sparkles },
          { label: '水位延迟', value: `${dashboard.data.lag?.seconds ?? 0}s`, delta: dashboard.data.roi != null ? `ROI ${dashboard.data.roi}` : '', icon: UsersRound },
        ]
      : emptyMetrics
  const points = series.data?.points ?? []
  const peak = Math.max(1, ...points.map((point) => point.revenueMinor))

  return <div className="workspace">
    <PageHeader eyebrow={api.demoMode ? '运营总览 · 2026 双十一' : '运营总览'} title="早上好，今天的增长脉搏稳定。" description={api.demoMode ? '126 个活动正在 3 个 Cell 中运行，暂无资金不变量告警。' : `当前租户可见 ${campaigns.data?.length ?? 0} 个活动、${releases.data?.length ?? 0} 个发布代际。`} actions={<><Link className="button secondary" to="/releases">查看发布态势</Link><Link className="button primary" to="/campaigns?create=true"><Sparkles size={16} />创建营销活动</Link></>} />
    <DemoBanner />
    {dashboard.isError && <StateBanner tone="error" title="指标加载失败" detail={problemDetail(dashboard.error)} />}
    {series.isError && <StateBanner tone="error" title="序列加载失败" detail={problemDetail(series.error)} />}
    <section className="metric-grid" aria-label="核心指标">
      {metrics.map(({ label, value, delta, icon: Icon }) => <article className="metric-card" key={label}><div className="metric-icon"><Icon size={18} /></div><p>{label}</p><strong>{value}</strong><span>{delta}</span></article>)}
    </section>
    <section className="dashboard-grid">
      <Panel className="pulse-panel"><PanelHeader eyebrow="实时经营" title="营销转化脉搏" aside={<Badge tone={api.demoMode ? 'warn' : points.length ? 'good' : 'info'}>{api.demoMode ? '演示' : points.length ? 'API' : '暂无序列'}</Badge>} />
        {api.demoMode
          ? <div className="pulse-chart" aria-label="最近十二小时转化趋势图">{[32,39,36,48,52,49,67,61,75,70,84,91].map((height,index) => <span key={index} style={{ '--height': `${height}%` } as React.CSSProperties} />)}</div>
          : points.length === 0
            ? <EmptyState title="暂无小时序列" detail="GET /api/v1/measurements/series 尚未返回数据点。" />
            : <div className="pulse-chart" aria-label="今日小时归因收入">{points.slice(-12).map((point) => <span key={point.at} style={{ '--height': `${Math.max(8, Math.round((point.revenueMinor / peak) * 100))}%` } as React.CSSProperties} title={`${point.at} · ${formatMinor(point.revenueMinor)}`} />)}</div>}
      </Panel>
      <Panel className="readiness-panel"><PanelHeader eyebrow={api.demoMode ? '发布代际 #1842' : '发布就绪'} title="Cell 就绪度" aside={<Link className="text-button" to="/releases">打开控制台 →</Link>} />{(api.demoMode ? [['华东 · A','100%','已激活','good'],['华北 · B','100%','已激活','good'],['华南 · C','67%','预热中 · 2/3','warn']] : (releases.data ?? []).slice(0, 3).map((item) => [item.manifest.cell, `${Math.min(100, item.readyReplicas * 34)}%`, item.state, item.state === 'ACTIVE' ? 'good' : 'warn'])).map(([name,width,state,tone]) => <div className="readiness" key={String(name)}><div><strong>{name}</strong><Badge tone={tone as 'good'|'warn'}>{state}</Badge></div><div className="progress"><i style={{ width: String(width) }} /></div></div>)}</Panel>
    </section>
  </div>
}
