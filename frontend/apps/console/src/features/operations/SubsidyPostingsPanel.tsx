import { useState } from 'react'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { DateTimePicker } from '../../components/DateTimePicker'
import { businessDayStart, displayTimestamp } from '../../components/dateTime'
import { FieldHint } from '../../components/FieldHint'
import { RecordTable, RecordTime } from '../../components/RecordTable'
import { Select } from '../../components/Select'
import { Button, EmptyState, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { api } from '../../shared/api/client'
import { problemDetail } from '../../shared/api/problem'
import { MAX_SUBSIDY_RANGE_MS } from '../../shared/api/schemas'
import { useAuth } from '../../shared/auth/useAuth'

const UNASSIGNED = '!unassigned'
const OPERATIONS = { CONFIRM: '补贴确认', REFUND: '退款或冲正' } as const

export function SubsidyPostingsPanel() {
  const auth = useAuth()
  const scope = JSON.stringify([auth.tenantId, auth.subject, auth.organizations, auth.shops, auth.permissions])
  if (api.demoMode || !auth.hasPermission('funding:reconcile')) return null
  return <SubsidyPostingSearch key={scope} scope={scope} />
}

function SubsidyPostingSearch({ scope }: { scope: string }) {
  const [params] = useSearchParams()
  const initialFrom = params.get('postingFrom') ?? ''
  const initialTo = params.get('postingTo') ?? ''
  const initialCampaign = params.get('postingCampaignId') ?? ''
  const initialSpan = Date.parse(initialTo) - Date.parse(initialFrom)
  const linkedRange = initialSpan > 0 && initialSpan <= MAX_SUBSIDY_RANGE_MS && /^[A-Za-z0-9_.:-]{0,64}$/.test(initialCampaign)
  const canList = useAuth().hasPermission('campaign:read')
  const campaigns = useQuery({ queryKey: ['subsidy-campaigns', scope], queryFn: api.campaigns, enabled: canList, retry: false })
  const [form, setForm] = useState(() => {
    if (linkedRange) return { from: initialFrom, to: initialTo, selection: initialCampaign }
    const now = new Date()
    return { from: businessDayStart(now).toISOString(), to: now.toISOString(), selection: '' }
  })
  const [applied, setApplied] = useState(() => (
    linkedRange ? { ...form, revision: 0 } : undefined
  ))
  const span = Date.parse(form.to) - Date.parse(form.from)
  const valid = span > 0 && span <= MAX_SUBSIDY_RANGE_MS
  const postings = useInfiniteQuery({
    queryKey: ['subsidy-postings', scope, applied],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => {
      const { from, to, selection } = applied!
      return api.subsidyPostings({ from, to, campaignId: selection && selection !== UNASSIGNED ? selection : undefined,
        unassigned: selection === UNASSIGNED, cursor: pageParam })
    },
    getNextPageParam: page => page.nextCursor ?? undefined,
    enabled: Boolean(applied),
    retry: false,
  })
  const items = [...new Map((postings.data?.pages.flatMap(page => page.items) ?? []).map(item => [item.ledgerId, item])).values()]
  const totals = new Map<string, bigint>()
  for (const item of items) totals.set(item.currency, (totals.get(item.currency) ?? 0n) + BigInt(item.amountMinor))
  const activityLabel = (id: string) => campaigns.data?.find(campaign => campaign.id === id)?.name ?? id

  return <Panel className="reconciliation-report">
    <PanelHeader title="补贴流水" />
    <form className="form-grid" onSubmit={event => {
      event.preventDefault()
      if (valid && !postings.isFetching) setApplied({ ...form, revision: (applied?.revision ?? 0) + 1 })
    }}>
      <div className="field"><span>入账开始</span><DateTimePicker label="入账开始" value={form.from} onChange={from => setForm(current => ({ ...current, from }))} describedBy="posting-range-hint" /></div>
      <div className="field"><span>入账结束</span><DateTimePicker label="入账结束" value={form.to} onChange={to => setForm(current => ({ ...current, to }))} describedBy="posting-range-hint" /></div>
      <div className="span-2"><FieldHint id="posting-range-hint">按北京时间选择，包含开始时间，结束时间不计入，最长 31 天。退款按实际入账时间冲减。</FieldHint></div>
      <label className="field"><span>活动归属</span><Select value={form.selection} onChange={event => setForm(current => ({ ...current, selection: event.target.value }))}>
        <option value="">全部可见流水</option><option value={UNASSIGNED}>仅待归属流水</option>
        {form.selection && form.selection !== UNASSIGNED && !campaigns.data?.some(campaign => campaign.id === form.selection) && <option value={form.selection}>{form.selection}</option>}
        {campaigns.data?.map(campaign => <option key={campaign.id} value={campaign.id}>{campaign.name} · {campaign.id}</option>)}
      </Select></label>
      <div className="toolbar subsidy-posting-actions"><Button tone="primary" type="submit" disabled={!valid || postings.isFetching}>查询补贴流水</Button></div>
      {!valid && <p className="field-hint span-2" role="status">{span > MAX_SUBSIDY_RANGE_MS ? '查询范围最长 31 天。' : '请填写有效的开始和结束时间，且结束须晚于开始。'}</p>}
    </form>
    {campaigns.isError && <p className="field-hint">活动列表读取失败，仍可查询全部可见或待归属流水。 <Button onClick={() => void campaigns.refetch()}>重试活动列表</Button></p>}
    {applied && <p className="field-hint">已查询范围 {displayTimestamp(applied.from)} 至 {displayTimestamp(applied.to)}。{applied.selection === UNASSIGNED ? '仅待归属流水' : applied.selection ? activityLabel(applied.selection) : '全部可见流水'}。</p>}
    {applied && postings.isPending && <StateBanner tone="loading" title="正在读取补贴流水" detail="金额以资金账本的已入账记录为准。" />}
    {postings.isError && <StateBanner tone="error" title="补贴流水读取失败" detail={problemDetail(postings.error)} />}
    {postings.isSuccess && items.length === 0 && <EmptyState title="没有匹配的补贴流水" detail="可调整入账时间或活动归属后重试。" />}
    {items.length > 0 && <>
      <p className="field-hint">已加载 {items.length} 笔。已加载净额 {Array.from(totals, ([currency, amount]) => `${currency} ${amount.toLocaleString('zh-CN')}`).join('；')}（最小货币单位）。</p>
      <RecordTable label="补贴流水" columns={['金额（最小货币单位）', '币种', '入账类型', '入账时间（北京时间）', '活动 ID', '出资账户', '资金申请 ID', '流水 ID']}>
        {items.map(item => <tr key={item.ledgerId}>
          <td>{BigInt(item.amountMinor).toLocaleString('zh-CN')}</td><td>{item.currency}</td><td>{OPERATIONS[item.operation]}</td>
          <td><RecordTime value={item.postedAt} /></td><td>{item.campaignId ?? '待归属'}</td><td>{item.resourceKey}</td>
          <td>{item.applicationId}</td><td>{item.ledgerId}</td>
        </tr>)}
      </RecordTable>
    </>}
    {postings.hasNextPage && <div className="toolbar"><Button onClick={() => void postings.fetchNextPage()} disabled={postings.isFetching}>{postings.isFetchNextPageError ? '重试加载更多' : '加载更多流水'}</Button></div>}
  </Panel>
}
