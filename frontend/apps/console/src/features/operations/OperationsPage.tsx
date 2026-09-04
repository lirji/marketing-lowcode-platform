import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, CircleDot, Copy, DatabaseZap, RefreshCw, Search, ShieldCheck, TriangleAlert } from 'lucide-react'
import { api } from '../../shared/api/client'
import { Badge, Button, DemoBanner, EmptyState, Modal, PageHeader, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { problemDetail } from '../../shared/api/problem'
import { useAuth } from '../../shared/auth/useAuth'
import type { ContactView, EnrollmentView, QuarantineView, TraceView } from '../../shared/api/schemas'

const tabs = ['全链路查询', 'Journey 实例', '触达记录', 'DLQ / 隔离区', '资金对账'] as const

export function OperationsPage() {
  const auth = useAuth()
  const [active, setActive] = useState<(typeof tabs)[number]>('全链路查询')
  const visibleTabs = tabs.filter((tab) => {
    if (tab === '全链路查询') return auth.hasPermission('trace:read')
    if (tab === 'Journey 实例') return auth.hasPermission('journey:read')
    if (tab === '触达记录') return auth.hasPermission('contact:read')
    if (tab === 'DLQ / 隔离区') return auth.hasPermission('event:read')
    return auth.hasPermission('funding:reconcile')
  })
  const current = visibleTabs.includes(active) ? active : visibleTabs[0]
  return <div className="workspace operations-page"><PageHeader eyebrow="运营与客服" title="沿同一业务键还原营销决策。" description="决策、报价、权益预占、旅程、触达与转化轨迹默认脱敏，并保留稳定原因码。" actions={<Button disabled title="敏感字段访问需独立工单接口"><ShieldCheck size={15} />敏感字段访问</Button>} /><DemoBanner /><div className="page-tabs" role="tablist">{visibleTabs.map((tab) => <button role="tab" aria-selected={current === tab} aria-controls={`tab-${tab}`} className={current === tab ? 'active' : ''} onClick={() => setActive(tab)} key={tab}>{tab}</button>)}</div>
    {current === '全链路查询' && <TraceSearch />}
    {current === 'Journey 实例' && <JourneyInstances />}
    {current === '触达记录' && <ContactAttempts />}
    {current === 'DLQ / 隔离区' && <DeadLetters />}
    {current === '资金对账' && <ReconciliationPanel />}
  </div>
}

function TraceSearch() {
  const [kind, setKind] = useState<'request' | 'order'>('request')
  const [searchId, setSearchId] = useState(api.demoMode ? 'req-20260902-8F2A' : '')
  const [searched, setSearched] = useState(api.demoMode)
  const trace = useMutation({ mutationFn: (id: string) => kind === 'order' ? api.traceByOrder(id) : api.traceByRequest(id) })
  const runSearch = () => { setSearched(true); if (!api.demoMode) trace.mutate(searchId.trim()) }
  return <div id="tab-全链路查询" role="tabpanel"><Panel className="ops-search"><div className="lookup"><label><span className="sr-only">查询类型</span><select value={kind} onChange={(event) => setKind(event.target.value as 'request' | 'order')}><option value="request">Request ID</option><option value="order">Order ID</option></select></label><label><Search size={16} /><span className="sr-only">查询值</span><input value={searchId} onChange={(event) => setSearchId(event.target.value)} onKeyDown={(event) => event.key === 'Enter' && runSearch()} /></label><Button tone="primary" onClick={runSearch} disabled={!searchId.trim()}>查询</Button></div></Panel>{trace.isError && <StateBanner tone="error" title="轨迹查询失败" detail={problemDetail(trace.error)} />}{searched && <TraceResult requestId={searchId} live={trace.data} />}</div>
}

function TraceResult({ requestId, live }: { requestId: string; live?: TraceView }) {
  const demoEvents = [
    ['10:31:08.041', 'Decision', '规则命中：家电类目 + PLUS 会员', 'generation #1842 · 14.2ms', 'good'],
    ['10:31:08.049', 'Offer', '满 500 减 80；平台 48 / 商家 32', 'OfferToken verified · terms v3', 'good'],
    ['10:31:08.112', 'Reserve', '预算与库存原子预占成功', 'application pa-91AC · fencing 42', 'good'],
    ['10:31:09.826', 'Confirm', '订单确认，预占转为已耗', 'order JD-8842****129 · ledger balanced', 'good'],
    ['10:31:10.014', 'Exposure', '实际优惠生效曝光已记录', 'experiment exp-ha-7 / B', 'info'],
  ]
  const events = live?.events?.length
    ? live.events.map((event) => [event.at ?? live.createdAt ?? '', event.type, event.title ?? event.type, event.detail ?? '', 'good'])
    : api.demoMode ? demoEvents : []
  return <div className="trace-layout"><Panel><PanelHeader eyebrow="TRACE" title="营销全链路" aside={<Badge tone={live ? 'good' : 'warn'}>{live ? 'API' : '演示'}</Badge>} /><div className="trace-id"><span>Request</span><code>{live?.requestId ?? requestId}</code><button type="button" aria-label="复制 Request ID" onClick={() => void navigator.clipboard.writeText(live?.requestId ?? requestId)}><Copy size={13} /></button><span>Subject</span><code>{live?.maskedSubject ?? (api.demoMode ? 'f84a6d11c29e…' : '—')}</code></div>
    {live && <dl className="trace-summary"><div><dt>Trace</dt><dd>{live.traceId ?? '—'}</dd></div><div><dt>Generation</dt><dd>{live.generation ?? '—'}</dd></div><div><dt>耗时</dt><dd>{live.durationMicros != null ? `${(live.durationMicros / 1000).toFixed(1)}ms` : '—'}</dd></div><div><dt>Terms</dt><dd>{live.termsVersion ?? '—'}</dd></div></dl>}
    {events.length === 0 ? <EmptyState title="没有逐步事件" detail="当前轨迹只有摘要字段，events 为空。" /> : <ol className="trace-timeline">{events.map(([time, type, title, detail, tone]) => <li key={`${type}-${title}`}><time>{time}</time><span className={tone}><CircleDot size={17} /></span><div><strong>{type} · {title}</strong><small>{detail}</small></div></li>)}</ol>}
  </Panel><aside><Panel><PanelHeader eyebrow="DECISION EXPLAIN" title="候选与裁决" />{live ? <pre className="trace-json">{JSON.stringify(live.candidates ?? live.pricing ?? {}, null, 2)}</pre> : <><dl className="trace-summary"><div><dt>候选</dt><dd>7</dd></div><div><dt>资格通过</dt><dd>3</dd></div><div><dt>互斥后</dt><dd>2</dd></div><div><dt>最终采用</dt><dd>1</dd></div></dl><div className="reason-codes"><Badge tone="good">CATEGORY_MATCH</Badge><Badge tone="good">PLUS_ELIGIBLE</Badge></div></>}</Panel></aside></div>
}

function JourneyInstances() {
  const [enrollmentId, setEnrollmentId] = useState('')
  const list = useQuery({ queryKey: ['enrollments'], queryFn: () => api.enrollments(), enabled: !api.demoMode })
  const lookup = useMutation({ mutationFn: api.enrollment })
  if (api.demoMode) return <Panel><PanelHeader eyebrow="ENROLLMENTS" title="运行中 Journey 实例" aside={<Badge tone="info">82,491 waiting</Badge>} /><div className="ops-list">{[['enr-81FA', '加购未支付召回', 'WAIT_TIMER · 18m12s', 'v3', '正常'], ['enr-922C', '新客七日培育', 'WAIT_EVENT · OrderPaid', 'v7', '正常']].map(([id, name, state, version, health]) => <button key={id} type="button"><CircleDot /><div><strong>{name}</strong><small>{id}</small></div><code>{state}</code><Badge tone={health === '正常' ? 'good' : 'warn'}>{health}</Badge><span>{version}</span></button>)}</div></Panel>
  const items = lookup.data ? [lookup.data] : (list.data ?? [])
  return <Panel><PanelHeader eyebrow="ENROLLMENTS" title="Journey 实例" aside={<Button onClick={() => void list.refetch()} disabled={list.isFetching}><RefreshCw size={14} />刷新</Button>} />
    <div className="lookup"><label><Search size={16} /><span className="sr-only">Enrollment ID</span><input value={enrollmentId} onChange={(event) => setEnrollmentId(event.target.value)} placeholder="enrollmentId，可点查单条" /></label><Button tone="primary" onClick={() => lookup.mutate(enrollmentId.trim())} disabled={!enrollmentId.trim() || lookup.isPending}>查询</Button></div>
    {list.isError && <StateBanner tone="error" title="实例列表加载失败" detail={problemDetail(list.error)} />}
    {lookup.isError && <StateBanner tone="error" title="实例查询失败" detail={problemDetail(lookup.error)} />}
    {items.length === 0 ? <EmptyState title="没有 Journey 实例" detail="列表来自 GET /api/v1/enrollments；也可按 enrollmentId 点查。" /> : <div className="ops-list">{items.map((item) => <EnrollmentCard key={item.enrollmentId} item={item} />)}</div>}
  </Panel>
}

function EnrollmentCard({ item }: { item: EnrollmentView }) {
  return <button type="button"><CircleDot /><div><strong>{item.journeyId}</strong><small>{item.enrollmentId} · {mask(item.subjectToken)}</small></div><code>{item.currentNodeId ?? '—'}</code><Badge tone={item.status === 'FAILED' ? 'danger' : item.status === 'COMPLETED' ? 'good' : 'info'}>{item.status}</Badge><span>v{item.journeyVersion}</span></button>
}

function ContactAttempts() {
  const [contactKey, setContactKey] = useState('')
  const list = useQuery({ queryKey: ['contacts'], queryFn: () => api.contacts(), enabled: !api.demoMode })
  const lookup = useMutation({ mutationFn: api.contact })
  if (api.demoMode) return <Panel><PanelHeader eyebrow="CONTACT ATTEMPTS" title="触达投递记录" /><div className="ops-list">{[['ct-8101', 'PUSH · 加购召回', 'DELIVERED', 'provider-1192', '34ms']].map(([id, name, state, detail, time]) => <button key={id} type="button"><CheckCircle2 /><div><strong>{name}</strong><small>{id}</small></div><code>{detail}</code><Badge tone="good">{state}</Badge><span>{time}</span></button>)}</div></Panel>
  const items = lookup.data ? [lookup.data] : (list.data ?? [])
  return <Panel><PanelHeader eyebrow="CONTACT ATTEMPTS" title="触达投递记录" aside={<Button onClick={() => void list.refetch()} disabled={list.isFetching}><RefreshCw size={14} />刷新</Button>} />
    <div className="lookup"><label><Search size={16} /><span className="sr-only">Contact key</span><input value={contactKey} onChange={(event) => setContactKey(event.target.value)} placeholder="contactKey，可点查单条" /></label><Button tone="primary" onClick={() => lookup.mutate(contactKey.trim())} disabled={!contactKey.trim() || lookup.isPending}>查询</Button></div>
    {list.isError && <StateBanner tone="error" title="触达列表加载失败" detail={problemDetail(list.error)} />}
    {lookup.isError && <StateBanner tone="error" title="触达查询失败" detail={problemDetail(lookup.error)} />}
    {items.length === 0 ? <EmptyState title="没有触达记录" detail="列表来自 GET /api/v1/contacts；也可按 contactKey 点查。" /> : <div className="ops-list">{items.map((item) => <ContactCard key={item.contactId} item={item} />)}</div>}
  </Panel>
}

function ContactCard({ item }: { item: ContactView }) {
  return <button type="button"><CheckCircle2 /><div><strong>{item.contactKey}</strong><small>{item.contactId}</small></div><code>{item.providerCode ?? item.providerRequestId ?? '—'}</code><Badge tone={item.state === 'DELIVERED' ? 'good' : item.state === 'FAILED' ? 'danger' : 'warn'}>{item.state}</Badge><span>{item.updatedAt ?? ''}</span></button>
}

function DeadLetters() {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const [confirmId, setConfirmId] = useState<string>()
  const items = useQuery({ queryKey: ['quarantine'], queryFn: api.quarantine, enabled: !api.demoMode && auth.hasPermission('event:read') })
  const replay = useMutation({
    mutationFn: api.replayQuarantine,
    onSuccess: () => { setConfirmId(undefined); void queryClient.invalidateQueries({ queryKey: ['quarantine'] }) },
  })
  if (api.demoMode) return <><StateBanner tone="warn" title="3 个分区存在待处理消息" detail="演示数据不会重放。" /><Panel><PanelHeader eyebrow="DLQ & QUARANTINE" title="隔离消息" /><div className="ops-list">{[['q-192', 'commerce.event.v1', 'AGGREGATE_VERSION_GAP', '可重放']].map(([id, topic, reason, state]) => <button key={id} type="button"><TriangleAlert /><div><strong>{topic}</strong><small>{id}</small></div><code>{reason}</code><Badge tone="good">{state}</Badge></button>)}</div></Panel></>
  return <>
    {items.isError && <StateBanner tone="error" title="隔离区加载失败" detail={problemDetail(items.error)} />}
    <Panel><PanelHeader eyebrow="DLQ & QUARANTINE" title="隔离消息" aside={<Button onClick={() => void items.refetch()} disabled={items.isFetching}><RefreshCw size={14} />刷新</Button>} />
      {(items.data ?? []).length === 0 ? <EmptyState title="没有隔离消息" detail="隔离区为空，或当前身份缺少 event:read。" /> : <div className="ops-list">{(items.data ?? []).map((item) => <QuarantineRow key={item.quarantineId} item={item} canReplay={auth.hasPermission('event:replay')} onReplay={() => setConfirmId(item.quarantineId)} />)}</div>}
    </Panel>
    {confirmId && <Modal title="重放隔离消息" description={confirmId} onClose={() => setConfirmId(undefined)}>
      <div className="danger-confirm">
        <p>重放仍会经过 schema 与同意校验，不会绕过资金不变量。</p>
        {replay.isError && <StateBanner tone="error" title="重放失败" detail={problemDetail(replay.error)} />}
        <footer className="modal-actions">
          <Button onClick={() => setConfirmId(undefined)}>取消</Button>
          <Button tone="danger" disabled={replay.isPending} onClick={() => replay.mutate(confirmId)}>确认重放</Button>
        </footer>
      </div>
    </Modal>}
  </>
}

function QuarantineRow({ item, canReplay, onReplay }: { item: QuarantineView; canReplay: boolean; onReplay: () => void }) {
  return <button type="button" onClick={canReplay ? onReplay : undefined}><TriangleAlert /><div><strong>{item.reasonCode}</strong><small>{item.quarantineId} · {item.receiptId ?? ''}</small></div><code>{item.createdAt ?? ''}</code><Badge tone={item.state === 'REPLAYED' ? 'good' : 'warn'}>{item.state}</Badge><span>{canReplay ? '重放' : ''}</span></button>
}

function ReconciliationPanel() {
  const reconcile = useQuery({ queryKey: ['reconciliation'], queryFn: api.reconciliation, enabled: !api.demoMode })
  if (api.demoMode) return <ReconciliationDemo />
  if (reconcile.isError) return <StateBanner tone="error" title="对账失败" detail={problemDetail(reconcile.error)} />
  if (!reconcile.data) return <EmptyState title="正在核对资金守恒" detail="读取 /api/v1/funding/reconciliation。" />
  return <StateBanner tone={reconcile.data.balanced ? 'success' : 'error'} title={reconcile.data.balanced ? '资金守恒成立' : '存在不变量差异'} detail={reconcile.data.violations[0] ?? `checkedAt ${reconcile.data.checkedAt ?? ''}`} />
}

function ReconciliationDemo() {
  return <div className="reconcile-grid"><Panel><PanelHeader eyebrow="LEDGER RECONCILIATION" title="资金守恒报告" aside={<Badge tone="good">PASS</Badge>} /><div className="reconcile-hero"><DatabaseZap size={32} /><div><strong>0</strong><span>不变量差异</span></div></div></Panel></div>
}

function mask(value?: string) {
  if (!value) return '—'
  return value.length <= 8 ? value : `${value.slice(0, 6)}…`
}
