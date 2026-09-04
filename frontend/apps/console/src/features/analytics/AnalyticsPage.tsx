import { useState } from 'react'
import { Area, AreaChart, Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Download, RefreshCw } from 'lucide-react'
import { Badge, Button, DemoBanner, EmptyState, PageHeader, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { useMutation, useQuery } from '@tanstack/react-query'
import { api } from '../../shared/api/client'
import { problemDetail } from '../../shared/api/problem'
import { useAuth } from '../../shared/auth/useAuth'

const trend = [
  { time: '00:00', revenue: 820, cost: 162 }, { time: '04:00', revenue: 1040, cost: 184 },
  { time: '08:00', revenue: 1880, cost: 301 }, { time: '12:00', revenue: 2460, cost: 398 },
  { time: '16:00', revenue: 2190, cost: 376 }, { time: '20:00', revenue: 2860, cost: 442 },
  { time: '24:00', revenue: 3140, cost: 481 },
]
const variants = [{ name: 'Control', conversion: 7.42, revenue: 128 }, { name: 'Variant A', conversion: 8.06, revenue: 143 }, { name: 'Variant B', conversion: 8.31, revenue: 151 }]

function windowStart(label: string) {
  const from = new Date()
  if (label === '7 天') from.setDate(from.getDate() - 7)
  else if (label === '30 天') from.setDate(from.getDate() - 30)
  else from.setHours(0, 0, 0, 0)
  return from
}

function formatTick(iso: string, label: string) {
  const date = new Date(iso)
  return label === '今天'
    ? date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    : date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
}

export function AnalyticsPage() {
  const auth = useAuth()
  const [window, setWindow] = useState('今天')
  const from = windowStart(window)
  const granularity = window === '今天' ? 'hour' : 'day'
  const dashboard = useQuery({ queryKey: ['analytics', window], queryFn: () => api.dashboard(from.toISOString(), new Date().toISOString()), enabled: !api.demoMode })
  const series = useQuery({
    queryKey: ['analytics-series', window, granularity],
    queryFn: () => api.series(from.toISOString(), new Date().toISOString(), granularity),
    enabled: !api.demoMode,
  })
  const recompute = useMutation({ mutationFn: api.recomputeAttribution })
  const canRecompute = !api.demoMode && auth.hasPermission('measurement:ingest')
  const revenue = dashboard.data ? (dashboard.data.attributedRevenueMinor / 100).toFixed(0) : '—'
  const cost = dashboard.data ? (dashboard.data.fundingCostMinor / 100).toFixed(0) : '—'
  const chart = (series.data?.points ?? []).map((point) => ({
    time: formatTick(point.at, window),
    revenue: point.revenueMinor / 100,
    cost: point.costMinor / 100,
  }))
  const counts = Object.entries(dashboard.data?.counts ?? {})
  const maxCount = Math.max(1, ...counts.map(([, value]) => value))

  return <div className="workspace analytics-page"><PageHeader eyebrow="衡量分析" title="从真实曝光到可对账 ROI。" description="事实可重放、更正可反转；分组不等于曝光，迟到数据通过水位与重算显式呈现。" actions={<><Button disabled={!canRecompute || recompute.isPending} title={api.demoMode ? '演示模式不可执行' : canRecompute ? '重算当前窗口归因' : '需要 measurement:ingest'} onClick={() => recompute.mutate()}><RefreshCw size={15} />重算归因</Button><Button disabled title="导出尚未接入"><Download size={15} />导出报告</Button></>} />
    <DemoBanner />
    {dashboard.isError && <StateBanner tone="error" title="KPI 加载失败" detail={problemDetail(dashboard.error)} />}
    {series.isError && <StateBanner tone="error" title="序列加载失败" detail={problemDetail(series.error)} />}
    {recompute.isError && <StateBanner tone="error" title="归因重算失败" detail={problemDetail(recompute.error)} />}
    {recompute.data && <StateBanner tone="success" title="归因已重算" detail={`conversions ${recompute.data.conversions} · credits ${recompute.data.credits}${recompute.data.completedAt ? ` · ${recompute.data.completedAt}` : ''}`} />}
    <div className="analytics-filter"><div className="segmented">{['今天','7 天','30 天'].map((item) => <button className={window === item ? 'active' : ''} onClick={() => setWindow(item)} key={item}>{item}</button>)}</div><select disabled={!api.demoMode} title={api.demoMode ? undefined : '活动过滤尚未接入'}><option>全部活动</option><option>双11家电主会场</option></select><select disabled={!api.demoMode} title={api.demoMode ? undefined : '归因策略过滤尚未接入'}><option>Last touch · 7d</option><option>First touch · 7d</option><option>Linear · 7d</option></select><Badge tone="good">水位延迟 {dashboard.data?.lag?.seconds ?? (api.demoMode ? 12 : '—')}s</Badge></div>
    <section className="analytics-metrics">{[['归因成交', api.demoMode ? '¥ 18.43M' : `¥ ${revenue}`, dashboard.data?.completeThrough ?? ''],['营销成本', api.demoMode ? '¥ 3.11M' : `¥ ${cost}`, ''],['ROI', api.demoMode ? '4.93' : String(dashboard.data?.roi ?? '—'), ''],['水位', `${dashboard.data?.lag?.seconds ?? (api.demoMode ? 12 : '—')}s`, '']].map(([label,value,delta]) => <Panel key={label}><p>{label}</p><strong>{value}</strong><span>{delta}</span></Panel>)}</section>
    <div className="analytics-grid">{api.demoMode ? <><Panel className="trend-chart"><PanelHeader eyebrow="REVENUE / COST" title="归因收入与补贴成本" aside={<Badge tone="info">CNY · 千元</Badge>} /><div className="chart-container"><ResponsiveContainer width="100%" height="100%"><AreaChart data={trend}><defs><linearGradient id="revenueFill" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="#168b80" stopOpacity={0.35}/><stop offset="100%" stopColor="#168b80" stopOpacity={0.02}/></linearGradient></defs><CartesianGrid stroke="#e9eeee" vertical={false}/><XAxis dataKey="time" tick={{ fontSize: 10 }} axisLine={false}/><YAxis tick={{ fontSize: 10 }} axisLine={false}/><Tooltip/><Legend/><Area type="monotone" dataKey="revenue" name="归因收入" stroke="#168b80" fill="url(#revenueFill)" strokeWidth={2}/><Area type="monotone" dataKey="cost" name="补贴成本" stroke="#d18a27" fill="transparent" strokeWidth={2}/></AreaChart></ResponsiveContainer></div></Panel>
      <Panel><PanelHeader eyebrow="FUNNEL" title="营销转化漏斗" /><div className="analytics-funnel">{[['资格决策','8.42M','100%'],['Offer 展示','4.18M','49.6%'],['权益应用','2.13M','25.3%'],['确认转化','684K','8.12%']].map(([label,value,width]) => <div key={label}><span>{label}</span><i><b style={{ width }} /></i><strong>{value}</strong></div>)}</div></Panel>
      <Panel className="experiment-chart"><PanelHeader eyebrow="EXPERIMENT · exp-ha-7" title="变体增量" aside={<Badge tone="good">SRM PASS</Badge>} /><div className="chart-container short"><ResponsiveContainer width="100%" height="100%"><BarChart data={variants}><CartesianGrid stroke="#e9eeee" vertical={false}/><XAxis dataKey="name" tick={{ fontSize: 10 }} axisLine={false}/><YAxis tick={{ fontSize: 10 }} axisLine={false}/><Tooltip/><Bar dataKey="conversion" name="转化率 %" fill="#3179ba" radius={[4,4,0,0]}/></BarChart></ResponsiveContainer></div></Panel>
      <Panel><PanelHeader eyebrow="ATTRIBUTION" title="触点贡献" /><div className="touch-list">{[['Offer 展示','38%','#168b80'],['Push 送达','27%','#3179ba'],['站内信点击','21%','#7659b1'],['权益到账','14%','#d18a27']].map(([label,value,color]) => <div key={label}><span>{label}</span><i><b style={{ width: value, background: color }} /></i><strong>{value}</strong></div>)}</div><StateBanner tone="info" title="退款更正已收敛" detail="最近 7 条 correction 已反转旧贡献并重算。" /></Panel></> : <>
      <Panel className="trend-chart"><PanelHeader eyebrow="REVENUE / COST" title="归因收入与补贴成本" aside={<Badge tone="info">CNY · 元</Badge>} />{chart.length === 0 ? <EmptyState title="暂无时间序列" detail="GET /api/v1/measurements/series 尚未返回数据点。" /> : <div className="chart-container"><ResponsiveContainer width="100%" height="100%"><AreaChart data={chart}><defs><linearGradient id="revenueFillLive" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="#168b80" stopOpacity={0.35}/><stop offset="100%" stopColor="#168b80" stopOpacity={0.02}/></linearGradient></defs><CartesianGrid stroke="#e9eeee" vertical={false}/><XAxis dataKey="time" tick={{ fontSize: 10 }} axisLine={false}/><YAxis tick={{ fontSize: 10 }} axisLine={false}/><Tooltip/><Legend/><Area type="monotone" dataKey="revenue" name="归因收入" stroke="#168b80" fill="url(#revenueFillLive)" strokeWidth={2}/><Area type="monotone" dataKey="cost" name="补贴成本" stroke="#d18a27" fill="transparent" strokeWidth={2}/></AreaChart></ResponsiveContainer></div>}</Panel>
      <Panel><PanelHeader eyebrow="COUNTS" title="事实类型拆分" />{counts.length === 0 ? <EmptyState title="暂无拆分" detail="dashboard.counts 为空。" /> : <div className="analytics-funnel">{counts.map(([label, value]) => <div key={label}><span>{label}</span><i><b style={{ width: `${Math.max(8, Math.round((value / maxCount) * 100))}%` }} /></i><strong>{value}</strong></div>)}</div>}</Panel>
    </>}</div>
  </div>
}
