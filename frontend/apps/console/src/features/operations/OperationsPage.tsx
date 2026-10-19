import { RecordTable, RecordTime } from '../../components/RecordTable'
import { FieldHint } from '../../components/FieldHint'
import { Select } from '../../components/Select'
import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Copy, DatabaseZap, RefreshCw, Search, ShieldCheck, TriangleAlert } from 'lucide-react'
import { api } from '../../shared/api/client'
import { Badge, Button, DemoBanner, EmptyState, Modal, PageHeader, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { problemDetail } from '../../shared/api/problem'
import { useAuth } from '../../shared/auth/useAuth'
import type { TraceView } from '../../shared/api/schemas'
import { PageTabs } from '../../components/PageTabs'
import { AwardIntentsPanel } from './AwardIntentsPanel'

const tabs = ['全链路查询', 'Journey 实例', '触达记录', '发放', 'DLQ / 隔离区', '资金对账'] as const

function tabFromQuery(tab: string | null): (typeof tabs)[number] | undefined {
  if (tab === 'awards') return '发放'
  return undefined
}

export function OperationsPage() {
  const auth = useAuth()
  const [params, setParams] = useSearchParams()
  const [active, setActive] = useState<(typeof tabs)[number]>(tabFromQuery(params.get('tab')) ?? '全链路查询')
  const visibleTabs = tabs.filter((tab) => {
    if (tab === '全链路查询' || tab === '发放') return auth.hasPermission('trace:read')
    if (tab === 'Journey 实例') return auth.hasPermission('journey:read')
    if (tab === '触达记录') return auth.hasPermission('contact:read')
    if (tab === 'DLQ / 隔离区') return auth.hasPermission('event:read')
    return auth.hasPermission('funding:reconcile')
  })
  const current = visibleTabs.includes(active) ? active : visibleTabs[0]
  const selectTab = (tab: (typeof tabs)[number]) => {
    setActive(tab)
    const next = new URLSearchParams(params)
    if (tab === '发放') next.set('tab', 'awards')
    else next.delete('tab')
    setParams(next, { replace: true })
  }
  return <div className="workspace operations-page"><PageHeader eyebrow="运营与客服" title="运营与客服" description="决策、报价、权益预占、旅程、触达与转化轨迹默认脱敏，并保留稳定原因码。" actions={<Button disabled title="敏感字段访问需独立工单接口"><ShieldCheck size={15} />敏感字段访问</Button>} /><DemoBanner /><PageTabs label="运营查询分类" items={visibleTabs.map((tab) => ({ value: tab, label: tab }))} value={current} onChange={selectTab}>
    {current === '全链路查询' && <TraceSearch />}
    {current === 'Journey 实例' && <JourneyInstances />}
    {current === '触达记录' && <ContactAttempts />}
    {current === '发放' && <AwardIntentsPanel />}
    {current === 'DLQ / 隔离区' && <DeadLetters />}
    {current === '资金对账' && <ReconciliationPanel />}
  </PageTabs></div>
}

function TraceSearch() {
  const [kind, setKind] = useState<'request' | 'order'>('request')
  const [searchId, setSearchId] = useState(api.demoMode ? 'req-20260902-8F2A' : '')
  const [searched, setSearched] = useState(api.demoMode)
  const trace = useMutation({ mutationFn: (id: string) => kind === 'order' ? api.traceByOrder(id) : api.traceByRequest(id) })
  const runSearch = () => { setSearched(true); if (!api.demoMode) trace.mutate(searchId.trim()) }
  return <div id="tab-全链路查询" role="tabpanel"><StateBanner tone="info" title="这是营销 Request ID 业务轨迹" detail="不是 Grafana Tempo。跨服务 span 请到 http://127.0.0.1:3001 的 Explore / Tempo 查看。" /><Panel className="ops-search"><div className="lookup"><label><span className="sr-only">查询类型</span><Select value={kind} onChange={(event) => setKind(event.target.value as 'request' | 'order')}><option value="request">请求 ID（Request ID）</option><option value="order">订单 ID（Order ID）</option></Select></label><label><Search size={16} /><span className="sr-only">查询值</span><input placeholder={kind === 'request' ? '填写完整请求 ID' : '填写完整订单 ID'} aria-describedby="trace-search-hint" value={searchId} onChange={(event) => setSearchId(event.target.value)} onKeyDown={(event) => event.key === 'Enter' && runSearch()} /></label><Button tone="primary" onClick={runSearch} disabled={!searchId.trim()}>查询</Button></div><FieldHint id="trace-search-hint">{kind === 'request' ? '请求 ID 标识一次营销处理请求，可在业务调用回执中获取。' : '订单 ID 是订单系统中的唯一编号。'} 按完整 ID 精确查询。</FieldHint></Panel>{trace.isError && <StateBanner tone="error" title="轨迹查询失败" detail={problemDetail(trace.error)} />}{searched && <TraceResult requestId={searchId} live={trace.data} />}</div>
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
    {events.length === 0 ? <EmptyState title="没有逐步事件" detail="当前轨迹只有摘要字段，events 为空。" /> : <RecordTable label="轨迹事件" columns={['事件时间', '事件类型', '事件标题', '事件详情']}>{events.map(([time, type, title, detail], index) => <tr key={`${index}-${type}`}><td>{time}</td><td>{type}</td><td>{title}</td><td>{detail || '—'}</td></tr>)}</RecordTable>}
  </Panel><aside><Panel><PanelHeader eyebrow="DECISION EXPLAIN" title="候选与裁决" />{live ? <pre className="trace-json">{JSON.stringify(live.candidates ?? live.pricing ?? {}, null, 2)}</pre> : <><dl className="trace-summary"><div><dt>候选</dt><dd>7</dd></div><div><dt>资格通过</dt><dd>3</dd></div><div><dt>互斥后</dt><dd>2</dd></div><div><dt>最终采用</dt><dd>1</dd></div></dl><div className="reason-codes"><Badge tone="good">CATEGORY_MATCH</Badge><Badge tone="good">PLUS_ELIGIBLE</Badge></div></>}</Panel></aside></div>
}

function JourneyInstances() {
  const [enrollmentId, setEnrollmentId] = useState('')
  const list = useQuery({ queryKey: ['enrollments'], queryFn: () => api.enrollments(), enabled: !api.demoMode })
  const lookup = useMutation({ mutationFn: api.enrollment })
  if (api.demoMode) return <Panel><PanelHeader eyebrow="ENROLLMENTS" title="运行中 Journey 实例" aside={<Badge tone="info">82,491 waiting</Badge>} /><RecordTable label="Journey 实例" columns={['旅程名称', '旅程实例 ID', '状态', '等待条件', '版本', '健康状态']}>{[['enr-81FA', '加购未支付召回', 'WAIT_TIMER', '18m12s', 'v3', '正常'], ['enr-922C', '新客七日培育', 'WAIT_EVENT', 'OrderPaid', 'v7', '正常']].map(([id, name, state, condition, version, health]) => <tr key={id}><td>{name}</td><td>{id}</td><td>{state}</td><td>{condition}</td><td>{version}</td><td><Badge tone="good">{health}</Badge></td></tr>)}</RecordTable></Panel>
  const items = lookup.data ? [lookup.data] : (list.data ?? [])
  return <Panel><PanelHeader eyebrow="ENROLLMENTS" title="Journey 实例" aside={<Button onClick={() => void list.refetch()} disabled={list.isFetching}><RefreshCw size={14} />刷新</Button>} />
    <div className="lookup"><label><Search size={16} /><span className="sr-only">旅程实例 ID（Enrollment ID）</span><input value={enrollmentId} onChange={(event) => setEnrollmentId(event.target.value)} aria-describedby="enrollment-search-hint" placeholder="填写完整旅程实例 ID" /></label><Button tone="primary" onClick={() => lookup.mutate(enrollmentId.trim())} disabled={!enrollmentId.trim() || lookup.isPending}>查询</Button></div><FieldHint id="enrollment-search-hint">对应下方列表的旅程实例 ID，填写完整值可精确查询该条记录。</FieldHint>
    {list.isError && <StateBanner tone="error" title="实例列表加载失败" detail={problemDetail(list.error)} />}
    {lookup.isError && <StateBanner tone="error" title="实例查询失败" detail={problemDetail(lookup.error)} />}
    {items.length === 0 ? <EmptyState title="没有 Journey 实例" detail="列表来自 GET /api/v1/enrollments；也可按 enrollmentId 点查。" /> : <RecordTable label="Journey 实例" columns={['旅程 ID', '旅程实例 ID', '当前节点 ID', '状态', '旅程版本', '主体标识（脱敏）', '是否重复', '投影时间（北京时间）']}>
      {items.map((item) => <tr key={item.enrollmentId}><td>{item.journeyId}</td><td>{item.enrollmentId}</td><td>{item.currentNodeId ?? '—'}</td><td><Badge tone={item.status === 'FAILED' ? 'danger' : item.status === 'COMPLETED' ? 'good' : 'info'}>{item.status}</Badge></td><td>{item.journeyVersion}</td><td>{mask(item.subjectToken)}</td><td>{item.duplicate === undefined ? '未知' : item.duplicate ? '是' : '否'}</td><td><RecordTime value={item.projectedAt} /></td></tr>)}
    </RecordTable>}
  </Panel>
}

function ContactAttempts() {
  const [contactKey, setContactKey] = useState('')
  const list = useQuery({ queryKey: ['contacts'], queryFn: () => api.contacts(), enabled: !api.demoMode })
  const lookup = useMutation({ mutationFn: api.contact })
  if (api.demoMode) return <Panel><PanelHeader eyebrow="CONTACT ATTEMPTS" title="触达投递记录" /><RecordTable label="触达记录" columns={['触达 ID', '渠道', '用途', '状态', '服务商请求 ID', '耗时']}>{[['ct-8101', 'PUSH', '加购召回', 'DELIVERED', 'provider-1192', '34ms']].map(([id, channel, purpose, state, detail, time]) => <tr key={id}><td>{id}</td><td>{channel}</td><td>{purpose}</td><td><Badge tone="good">{state}</Badge></td><td>{detail}</td><td>{time}</td></tr>)}</RecordTable></Panel>
  const items = lookup.data ? [lookup.data] : (list.data ?? [])
  return <Panel><PanelHeader eyebrow="CONTACT ATTEMPTS" title="触达投递记录" aside={<Button onClick={() => void list.refetch()} disabled={list.isFetching}><RefreshCw size={14} />刷新</Button>} />
    <div className="lookup"><label><Search size={16} /><span className="sr-only">触达标识（Contact key）</span><input value={contactKey} onChange={(event) => setContactKey(event.target.value)} aria-describedby="contact-search-hint" placeholder="填写完整触达标识" /></label><Button tone="primary" onClick={() => lookup.mutate(contactKey.trim())} disabled={!contactKey.trim() || lookup.isPending}>查询</Button></div><FieldHint id="contact-search-hint">对应下方列表的触达标识，填写完整值可精确查询该条记录。</FieldHint>
    {list.isError && <StateBanner tone="error" title="触达列表加载失败" detail={problemDetail(list.error)} />}
    {lookup.isError && <StateBanner tone="error" title="触达查询失败" detail={problemDetail(lookup.error)} />}
    {items.length === 0 ? <EmptyState title="没有触达记录" detail="列表来自 GET /api/v1/contacts；也可按 contactKey 点查。" /> : <RecordTable label="触达记录" columns={['触达标识', '触达 ID', '状态', '服务商响应码', '服务商请求 ID', '更新时间（北京时间）']}>
      {items.map((item) => <tr key={item.contactId}><td>{item.contactKey}</td><td>{item.contactId}</td><td><Badge tone={item.state === 'DELIVERED' ? 'good' : item.state === 'FAILED' ? 'danger' : 'warn'}>{item.state}</Badge></td><td>{item.providerCode ?? '—'}</td><td>{item.providerRequestId ?? '—'}</td><td><RecordTime value={item.updatedAt} /></td></tr>)}
    </RecordTable>}
  </Panel>
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
  if (api.demoMode) return <><StateBanner tone="warn" title="3 个分区存在待处理消息" detail="演示数据不会重放。" /><Panel><PanelHeader eyebrow="DLQ & QUARANTINE" title="隔离消息" /><RecordTable label="隔离消息" columns={['隔离消息 ID', '主题', '原因码', '状态', '操作']} actions>{[['q-192', 'commerce.event.v1', 'AGGREGATE_VERSION_GAP', '可重放']].map(([id, topic, reason, state]) => <tr key={id}><td>{id}</td><td>{topic}</td><td>{reason}</td><td><Badge tone="good">{state}</Badge></td><td><Button disabled title="演示模式不可重放">重放</Button></td></tr>)}</RecordTable></Panel></>
  return <>
    {items.isError && <StateBanner tone="error" title="隔离区加载失败" detail={problemDetail(items.error)} />}
    <Panel><PanelHeader eyebrow="DLQ & QUARANTINE" title="隔离消息" aside={<Button onClick={() => void items.refetch()} disabled={items.isFetching}><RefreshCw size={14} />刷新</Button>} />
      {(items.data ?? []).length === 0 ? <EmptyState title="没有隔离消息" detail="隔离区为空，或当前身份缺少 event:read。" /> : <RecordTable label="隔离消息" columns={['隔离消息 ID', '回执 ID', '原因码', '状态', '创建时间（北京时间）', '重放时间（北京时间）', '操作']} actions>
        {(items.data ?? []).map((item) => <tr key={item.quarantineId}><td>{item.quarantineId}</td><td>{item.receiptId ?? '—'}</td><td>{item.reasonCode}</td><td><Badge tone={item.state === 'REPLAYED' ? 'good' : 'warn'}>{item.state}</Badge></td><td><RecordTime value={item.createdAt} /></td><td><RecordTime value={item.replayedAt} /></td><td><Button disabled={!auth.hasPermission('event:replay')} title={auth.hasPermission('event:replay') ? '重放此消息' : '缺少 event:replay 权限'} onClick={() => setConfirmId(item.quarantineId)}>重放</Button></td></tr>)}
      </RecordTable>}
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

export function ReconciliationPanel() {
  const reconcile = useQuery({ queryKey: ['reconciliation'], queryFn: api.reconciliation, enabled: !api.demoMode })
  if (api.demoMode) return <ReconciliationDemo />
  if (reconcile.isError) return <StateBanner tone="error" title="对账失败" detail={problemDetail(reconcile.error)} />
  if (!reconcile.data) return <EmptyState title="正在核对资金守恒" detail="读取 /api/v1/funding/reconciliation。" />
  return <Panel className="reconciliation-report">
    <PanelHeader eyebrow="LEDGER RECONCILIATION" title={reconcile.data.balanced ? '本货主资金守恒成立' : '存在不变量差异'} aside={<Badge tone={reconcile.data.balanced ? 'good' : 'danger'}>{reconcile.data.balanced ? 'PASS' : `${reconcile.data.violations.length} VIOLATIONS`}</Badge>} />
    <dl className="reconciliation-summary">
      <div><dt>Ledger movement</dt><dd>{reconcile.data.ledgerMovement ?? '—'}</dd></div>
      <div><dt>Checked at</dt><dd>{reconcile.data.checkedAt ?? '—'}</dd></div>
    </dl>
    {reconcile.data.violations.length > 0
      ? <ol className="reconciliation-violations" aria-label="全部对账差异">{reconcile.data.violations.map((violation, index) => <li key={`${index}-${violation}`}><TriangleAlert size={15} /><span>{violation}</span></li>)}</ol>
      : <StateBanner tone="success" title="未发现差异" detail="本次校验返回 violations=[]。" />}
    <p className="reconciliation-footnote">有差异请按业务键到权益账本或流程实例继续排查；对账只报告差异，不执行补发。</p>
  </Panel>
}

function ReconciliationDemo() {
  return <div className="reconcile-grid"><Panel><PanelHeader eyebrow="LEDGER RECONCILIATION" title="资金守恒报告" aside={<Badge tone="good">PASS</Badge>} /><div className="reconcile-hero"><DatabaseZap size={32} /><div><strong>0</strong><span>不变量差异</span></div></div></Panel></div>
}

function mask(value?: string) {
  if (!value) return '—'
  return value.length <= 8 ? value : `${value.slice(0, 6)}…`
}
