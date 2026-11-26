import { useState } from 'react'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { useLocation, useSearchParams } from 'react-router-dom'
import { DateTimePicker } from '../../components/DateTimePicker'
import { businessDayStart, displayTimestamp } from '../../components/dateTime'
import { FieldHint } from '../../components/FieldHint'
import { RecordTable, RecordTime } from '../../components/RecordTable'
import { Select } from '../../components/Select'
import { Button, EmptyState, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { ApiProblem, api } from '../../shared/api/client'
import { problemDetail } from '../../shared/api/problem'
import { MAX_SUBSIDY_ORDER_ID_LENGTH, MAX_SUBSIDY_RANGE_MS } from '../../shared/api/schemas'
import { releaseScopeKey } from '../../shared/api/useReleasePages'
import { useAuth } from '../../shared/auth/useAuth'
import { downloadCsv, formatCsv } from '../../shared/downloadCsv'

const UNASSIGNED = '!unassigned'
const INITIAL_LINKED_RANGE_MS = 7 * 24 * 60 * 60 * 1_000
const OPERATIONS = { CONFIRM: '补贴确认', REFUND: '退款或冲正' } as const
const POSTING_COLUMNS = ['金额（最小货币单位）', '币种', '入账类型', '入账时间（北京时间）', '活动 ID', '出资账户', '资金申请号', '流水号']
const PAGINATION = { PARTIAL: '还有下一页', EXHAUSTED: '已读取至最后一页' }
const BINDING_STATUS = {
  UNBOUND: '待绑定', BOUND: '已绑定', EVIDENCE_MISSING: '首次回执待核查', CHECKOUT_BOUND: '试点订单已绑定',
} as const
const BINDING_COLUMNS = ['交易订单号', '绑定状态', '资金申请号', '活动 ID', '首次回执版本', '绑定时间（北京时间）']

export function SubsidyPostingsPanel() {
  const auth = useAuth()
  const scope = releaseScopeKey(auth)
  if (api.demoMode || !auth.hasPermission('funding:reconcile')) return null
  return <SubsidyPostingSearch key={scope} scope={scope} tenantId={auth.tenantId} />
}

function SubsidyPostingSearch({ scope, tenantId }: { scope: string; tenantId: string }) {
  const [params] = useSearchParams()
  const { state } = useLocation()
  const initialOrder = state?.postingScope === scope && typeof state.postingOrderId === 'string'
    && Array.from(state.postingOrderId).length <= MAX_SUBSIDY_ORDER_ID_LENGTH ? state.postingOrderId : ''
  const initialFrom = params.get('postingFrom') ?? ''
  const initialTo = params.get('postingTo') ?? ''
  const initialCampaign = params.get('postingCampaignId') ?? ''
  const initialSpan = Date.parse(initialTo) - Date.parse(initialFrom)
  const linkedRange = initialSpan > 0 && initialSpan <= MAX_SUBSIDY_RANGE_MS && /^[A-Za-z0-9_.:-]{0,64}$/.test(initialCampaign)
  const linkedRecent = !params.has('postingFrom') && !params.has('postingTo')
    && /^[A-Za-z0-9_.:-]{0,64}$/.test(initialCampaign) && Boolean(initialCampaign || initialOrder)
  const canList = useAuth().hasPermission('campaign:read')
  const campaigns = useQuery({ queryKey: ['subsidy-campaigns', scope], queryFn: api.campaigns, enabled: canList, retry: false })
  const [form, setForm] = useState(() => {
    if (linkedRange) return { from: initialFrom, to: initialTo, selection: initialCampaign, orderId: initialOrder }
    const now = new Date()
    if (linkedRecent) return { from: new Date(now.getTime() - INITIAL_LINKED_RANGE_MS).toISOString(),
      to: now.toISOString(), selection: initialCampaign, orderId: initialOrder }
    return { from: businessDayStart(now).toISOString(), to: now.toISOString(), selection: '', orderId: initialOrder }
  })
  const [applied, setApplied] = useState(() => (
    linkedRange || linkedRecent ? { ...form, revision: 0 } : undefined
  ))
  const span = Date.parse(form.to) - Date.parse(form.from)
  const validOrder = Array.from(form.orderId.trim()).length <= MAX_SUBSIDY_ORDER_ID_LENGTH
  const validRange = span > 0 && span <= MAX_SUBSIDY_RANGE_MS
  const postings = useInfiniteQuery({
    queryKey: ['subsidy-postings', scope, applied],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => {
      const { from, to, selection, orderId } = applied!
      return api.subsidyPostings({ from, to, campaignId: selection && selection !== UNASSIGNED ? selection : undefined,
        orderId: orderId || undefined, unassigned: selection === UNASSIGNED, cursor: pageParam })
    },
    getNextPageParam: page => page.nextCursor ?? undefined,
    enabled: Boolean(applied),
    retry: false,
  })
  const items = [...new Map((postings.data?.pages.flatMap(page => page.items) ?? []).map(item => [item.ledgerId, item])).values()]
  const totals = new Map<string, bigint>()
  for (const item of items) totals.set(item.currency, (totals.get(item.currency) ?? 0n) + BigInt(item.amountMinor))
  const activityLabel = (id: string) => campaigns.data?.find(campaign => campaign.id === id)?.name ?? id
  const appliedSelection = applied?.selection === UNASSIGNED ? '仅待归属流水'
    : applied?.selection ? activityLabel(applied.selection) : '全部可见流水'

  function downloadPostings() {
    if (!applied || !postings.isSuccess || postings.isFetching || items.length === 0) return
    const queryScope = [tenantId, applied.from, applied.to, applied.selection === UNASSIGNED ? '' : applied.selection,
      applied.selection === UNASSIGNED ? '仅待归属流水' : '可见流水', applied.orderId,
      postings.hasNextPage ? PAGINATION.PARTIAL : PAGINATION.EXHAUSTED]
    const file = formatCsv([
      [...POSTING_COLUMNS, '入账时间（UTC）', '租户 ID', '入账开始（UTC，含）', '入账结束（UTC，不含）', '活动筛选 ID', '归属范围', '订单号筛选', '分页状态'],
      ...items.map(item => [BigInt(item.amountMinor), item.currency, OPERATIONS[item.operation], displayTimestamp(item.postedAt),
        item.campaignId ?? '待归属', item.resourceKey, item.applicationNo ?? item.applicationId, item.ledgerNo ?? item.ledgerId, item.postedAt, ...queryScope]),
    ])
    downloadCsv(file, `subsidy-postings-${new Date(applied.to).toISOString().replaceAll(':', '-')}.csv`)
  }

  return <Panel className="reconciliation-report">
    <PanelHeader title="补贴流水" />
    <form className="form-grid" onSubmit={event => {
      event.preventDefault()
      if (validRange && validOrder && !postings.isFetching) {
        setApplied({ ...form, orderId: form.orderId.trim(), revision: (applied?.revision ?? 0) + 1 })
      }
    }}>
      <div className="field"><span>入账开始</span><DateTimePicker label="入账开始" value={form.from} onChange={from => setForm(current => ({ ...current, from }))} describedBy="posting-range-hint" /></div>
      <div className="field"><span>入账结束</span><DateTimePicker label="入账结束" value={form.to} onChange={to => setForm(current => ({ ...current, to }))} describedBy="posting-range-hint" /></div>
      <div className="span-2"><FieldHint id="posting-range-hint">按北京时间选择，包含开始时间，结束时间不计入，最长 31 天。退款按实际入账时间冲减。</FieldHint></div>
      <label className="field"><span>活动归属</span><Select value={form.selection} onChange={event => setForm(current => ({ ...current, selection: event.target.value }))}>
        <option value="">全部可见流水</option><option value={UNASSIGNED}>仅待归属流水</option>
        {form.selection && form.selection !== UNASSIGNED && !campaigns.data?.some(campaign => campaign.id === form.selection) && <option value={form.selection}>{form.selection}</option>}
        {campaigns.data?.map(campaign => <option key={campaign.id} value={campaign.id}>{campaign.name} · {campaign.id}</option>)}
      </Select></label>
      <label className="field"><span>订单号或资金申请号</span><input aria-label="订单号" aria-describedby="posting-order-hint" aria-invalid={!validOrder}
        value={form.orderId} onChange={event => setForm(current => ({ ...current, orderId: event.target.value }))} />
        <FieldHint id="posting-order-hint">填写完整的交易订单号、原订单 ID 或资金申请号，区分大小写。</FieldHint></label>
      <div className="action-bar toolbar subsidy-posting-actions span-2"><Button tone="primary" type="submit" disabled={!validRange || !validOrder || postings.isFetching}>查询补贴流水</Button></div>
      {!validRange && <p className="field-hint span-2" role="status">{span > MAX_SUBSIDY_RANGE_MS ? '查询范围最长 31 天。' : '请填写有效的开始和结束时间，且结束须晚于开始。'}</p>}
      {!validOrder && <p className="field-hint span-2" role="status">订单号最多 {MAX_SUBSIDY_ORDER_ID_LENGTH} 个字符。</p>}
    </form>
    {campaigns.isError && <p className="field-hint">活动列表读取失败，仍可查询全部可见或待归属流水。 <Button onClick={() => void campaigns.refetch()}>重试活动列表</Button></p>}
    {applied && <p className="field-hint">已查询范围 {displayTimestamp(applied.from)} 至 {displayTimestamp(applied.to)}。{appliedSelection}。</p>}
    {applied?.orderId && <p className="field-hint subsidy-posting-order"><span>订单号 {applied.orderId}</span></p>}
    {applied && postings.isPending && <StateBanner tone="loading" title="正在读取补贴流水" detail="金额以资金账本的已入账记录为准。" />}
    {postings.isError && <StateBanner tone="error" title="补贴流水读取失败" detail={problemDetail(postings.error)} />}
    {postings.isSuccess && items.length === 0 && <EmptyState title="没有匹配的补贴流水" detail="可调整入账时间或活动归属后重试。" />}
    {items.length > 0 && <>
      <p className="field-hint">已加载 {items.length} 笔。已加载净额 {Array.from(totals, ([currency, amount]) => `${currency} ${amount.toLocaleString('zh-CN')}`).join('；')}（最小货币单位）。</p>
      <RecordTable label="补贴流水" columns={POSTING_COLUMNS}>
        {items.map(item => <tr key={item.ledgerId}>
          <td>{BigInt(item.amountMinor).toLocaleString('zh-CN')}</td><td>{item.currency}</td><td>{OPERATIONS[item.operation]}</td>
          <td><RecordTime value={item.postedAt} /></td><td>{item.campaignId ?? '待归属'}</td><td>{item.resourceKey}</td>
          <td>{item.applicationNo ?? item.applicationId}</td><td>{item.ledgerNo ?? item.ledgerId}</td>
        </tr>)}
      </RecordTable>
    </>}
    {(postings.hasNextPage || items.length > 0) && <div className="action-bar toolbar">
      {postings.hasNextPage && <Button onClick={() => void postings.fetchNextPage()} disabled={postings.isFetching}>{postings.isFetchNextPageError ? '重试加载更多' : '加载更多流水'}</Button>}
      {items.length > 0 && <Button onClick={downloadPostings} disabled={!postings.isSuccess || postings.isFetching} aria-describedby="posting-export-hint">导出已加载流水</Button>}
    </div>}
    {items.length > 0 && <p className="field-hint" id="posting-export-hint">CSV 包含已加载的流水及查询范围。使用表格软件时，可将金额列按文本导入以保留完整精度。</p>}
    {applied?.orderId && <OrderBindingEvidence scope={scope} orderId={applied.orderId}
      selection={applied.selection} revision={applied.revision} />}
  </Panel>
}

function OrderBindingEvidence({ scope, orderId, selection, revision }: {
  scope: string; orderId: string; selection: string; revision: number
}) {
  const bindings = useInfiniteQuery({
    queryKey: ['subsidy-order-bindings', scope, orderId, selection, revision],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => api.orderBindingEvidence({
      orderId, campaignId: selection && selection !== UNASSIGNED ? selection : undefined,
      unassigned: selection === UNASSIGNED, after: pageParam,
    }),
    getNextPageParam: page => page.nextCursor ?? undefined,
    retry: false,
  })
  const items = [...new Map((bindings.data?.pages.flatMap(page => page.items) ?? []).map(item => [item.applicationId, item])).values()]
  const unsupported = bindings.error instanceof ApiProblem && bindings.error.status === 404
  return <section aria-label="订单绑定证据">
    <h3>订单绑定证据</h3>
    <p className="field-hint">可查询尚未入账的预算申请。试点订单已绑定表示付款前已核对交易订单。已绑定表示首次交易回执可核对。</p>
    {bindings.isPending && <StateBanner tone="loading" title="正在读取绑定证据" detail="可继续查看补贴流水。" />}
    {bindings.isError && !bindings.data && (unsupported
      ? <p className="field-hint">当前资金服务尚未提供订单绑定查询。</p>
      : <StateBanner tone="error" title="绑定证据读取失败" detail={problemDetail(bindings.error)}
        action={<Button onClick={() => void bindings.refetch()}>重试绑定查询</Button>} />)}
    {bindings.isSuccess && items.length === 0 && <EmptyState title="没有匹配的预算申请" detail="请核对订单号、活动归属或当前门店范围。" />}
    {items.length > 0 && <RecordTable label="订单绑定证据" columns={BINDING_COLUMNS}>
      {items.map(item => <tr key={item.applicationId}>
        <td>{item.orderNo ?? '—'}</td><td>{BINDING_STATUS[item.status]}</td><td>{item.applicationNo ?? item.applicationId}</td><td>{item.campaignId ?? '待归属'}</td>
        <td>{item.evidenceVersion ?? '—'}</td><td>{item.boundAt ? <RecordTime value={item.boundAt} /> : '—'}</td>
      </tr>)}
    </RecordTable>}
    {bindings.isFetchNextPageError && <StateBanner tone="error" title="下一页绑定证据读取失败" detail={problemDetail(bindings.error)} />}
    {bindings.hasNextPage && <div className="action-bar toolbar">
      <Button onClick={() => void bindings.fetchNextPage()} disabled={bindings.isFetching}>
        {bindings.isFetchNextPageError ? '重试加载绑定证据' : '加载更多绑定证据'}
      </Button>
    </div>}
  </section>
}
