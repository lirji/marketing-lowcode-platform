import { useRef, useState } from 'react'
import { AwardFulfillmentPanel } from './AwardFulfillmentPanel'
import { AWARD_ORDER_LABELS } from './awardFulfillmentStatus'
import { RecordTable, RecordTime } from '../../components/RecordTable'
import { Select } from '../../components/Select'
import { useSearchParams } from 'react-router-dom'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { Copy, ExternalLink } from 'lucide-react'
import { Badge, Button, EmptyState, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { api } from '../../shared/api/client'
import { problemDetail } from '../../shared/api/problem'
import { useAuth } from '../../shared/auth/useAuth'
import { benefitOrderHref, riskDecisionHref } from './awardIntentLinks'
import { awardIntentViewSchema, type AwardIntentView } from '../../shared/api/schemas'

const PAGE_SIZE = 20
const DELIVERY_STATUS = awardIntentViewSchema.shape.status.enum
const DELIVERY_MODE = awardIntentViewSchema.shape.deliveryMode.enum

function statusTone(status: AwardIntentView['status']) {
  if (status === DELIVERY_STATUS.SENT) return 'info' as const
  if (status === DELIVERY_STATUS.DEAD || status === DELIVERY_STATUS.RISK_BLOCKED) return 'danger' as const
  return 'warn' as const
}

function statusLabel(item: AwardIntentView) {
  switch (item.status) {
    case DELIVERY_STATUS.RISK_BLOCKED: return '风控拦截'
    case DELIVERY_STATUS.DEAD: return '投递已停止'
    case DELIVERY_STATUS.PENDING: return '等待投递'
    case DELIVERY_STATUS.SENT:
      if (item.deliveryResult === 'CENTER_ACCEPTED') return '权益中心已受理'
      if (item.deliveryResult === 'LEGACY_OWNED') return '由原系统处理'
      if (item.deliveryResult === 'SHADOW_RECORDED') return '仅记录'
      return '投递结果待核实'
  }
}

const DELIVERY_LABELS = { LEGACY_OWNED: '原系统负责', SHADOW_RECORDED: '影子记录', CENTER_ENQUEUED: '已入投递队列', CENTER_ACCEPTED: '受理回执已返回' } as const

function riskBannerTitle(action?: AwardIntentView['riskAction']) {
  if (action === 'REJECT') return '风控拒绝，未写出发放指令'
  if (action === 'CHALLENGE') return '风控挑战，未写出发放指令'
  if (action === 'REVIEW') return '风控待审，未写出发放指令'
  if (action === 'UNAVAILABLE') return '风控不可用，未写出发放指令'
  return '风控拦截，未写出发放指令'
}

function shortHash(value: string) {
  return value.length <= 12 ? value : `${value.slice(0, 8)}…`
}

export function AwardIntentsPanel() {
  const auth = useAuth()
  const [selectedIntent, setSelectedIntent] = useState<string>()
  const resultTrigger = useRef<HTMLButtonElement>(null)
  const [params, setParams] = useSearchParams()
  const campaignId = params.get('campaignId')?.trim() ?? ''
  const canRead = auth.hasPermission('trace:read')
  const campaigns = useQuery({
    queryKey: ['campaigns'],
    queryFn: api.campaigns,
    enabled: !api.demoMode && canRead,
  })
  const intents = useInfiniteQuery({
    queryKey: ['award-intents', campaignId],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => api.awardIntents(campaignId, { limit: PAGE_SIZE, cursor: pageParam }),
    getNextPageParam: (lastPage) => (lastPage.length === PAGE_SIZE ? lastPage[lastPage.length - 1]?.intentId : undefined),
    enabled: !api.demoMode && canRead && Boolean(campaignId),
  })
  const rows = intents.data?.pages.flat() ?? []
  const selectedInList = (campaigns.data ?? []).some((item) => item.id === campaignId)

  const selectCampaign = (next: string) => {
    setSelectedIntent(undefined)
    const copy = new URLSearchParams(params)
    copy.set('tab', 'awards')
    if (next) copy.set('campaignId', next)
    else copy.delete('campaignId')
    setParams(copy, { replace: true })
  }

  // 禁用查询不会清除历史缓存；静态预览必须同时隔离旧记录及其详情/刷新入口。
  if (api.demoMode) return <Panel><PanelHeader eyebrow="AWARD INTENTS" title="发放指令" aside={<Badge tone="info">只读</Badge>} /><StateBanner tone="info" title="演示模式不查询真实发放指令" detail="连接营销服务后可按活动读取实际发放指令。" /></Panel>

  return (
    <div id="tab-发放" role="tabpanel" className="award-intents-panels">
      <Panel>
        <PanelHeader eyebrow="AWARD INTENTS" title="发放指令" aside={<Badge tone="info">只读</Badge>} />
        {!api.demoMode && (
          <label className="field award-campaign-field">
            <span>活动</span>
            <Select aria-label="活动" value={campaignId} onChange={(event) => selectCampaign(event.target.value)} disabled={campaigns.isPending}>
              <option value="">请选择活动</option>
              {campaignId && !selectedInList && <option value={campaignId}>{campaignId}</option>}
              {(campaigns.data ?? []).map((item) => (
                <option key={item.id} value={item.id}>{item.name} · {item.id}</option>
              ))}
            </Select>
          </label>
        )}
        {campaigns.isError && <StateBanner tone="error" title="活动列表加载失败" detail={problemDetail(campaigns.error)} />}
        {intents.isError && <StateBanner tone="error" title="发放指令加载失败" detail={problemDetail(intents.error)} />}
        {!api.demoMode && !campaignId && <EmptyState title="请先选择活动" detail="发放列表必须带 campaignId，未选择时不会请求。" />}
        {!api.demoMode && campaignId && !intents.isPending && rows.length === 0 && !intents.isError && (
          <EmptyState
            title="本活动尚无发放指令"
            detail="AwardIntent 由运行时或内部接口写出。控制台只读，不会提交发奖，也没有新建表单。"
          />
        )}
        {rows.length > 0 && (
          <RecordTable label="发放指令" columns={['来源请求 ID', '指令 ID', '活动 ID', '来源系统', '定义版本', '发放模式', '投递状态', '最近发放结果', '核验时间（北京时间）', '主体摘要（脱敏）', '投递回执', '尝试次数', '权益订单号', '风控处置', '风控原因', '风控决策 ID', '最近错误', '创建时间（北京时间）', '更新时间（北京时间）', '发送时间（北京时间）', '操作']} actions>
            {rows.map((item) => <AwardIntentRow key={item.intentId} item={item} expanded={selectedIntent === item.intentId} onObserve={(trigger) => { resultTrigger.current = trigger; setSelectedIntent(item.intentId) }} />)}
          </RecordTable>
        )}
        {intents.hasNextPage && (
          <div className="award-intent-more">
            <Button onClick={() => void intents.fetchNextPage()} disabled={intents.isFetchingNextPage}>加载更多</Button>
          </div>
        )}
      </Panel>
      {selectedIntent && rows.some((item) => item.intentId === selectedIntent) && <AwardFulfillmentPanel key={selectedIntent} intentId={selectedIntent} onClose={() => { setSelectedIntent(undefined); resultTrigger.current?.focus() }} />}
    </div>
  )
}

function AwardIntentRow({ item, expanded, onObserve }: { item: AwardIntentView; expanded: boolean; onObserve: (trigger: HTMLButtonElement) => void }) {
  const orderHref = benefitOrderHref(item)
  const riskHref = riskDecisionHref(item)
  const blocked = item.status === DELIVERY_STATUS.RISK_BLOCKED
  const canObserve = item.deliveryMode === DELIVERY_MODE.CENTER && !blocked
  const observation = canObserve ? item.fulfillment : undefined
  const observedLabel = observation?.status ? AWARD_ORDER_LABELS[observation.status] : '结果未知'
  return (
    <tr>
      <td>{item.sourceRequestId}</td><td>{item.intentId}</td><td>{item.campaignId}</td><td>{item.sourceSystem}</td><td>{item.definitionVersion}</td><td>{item.deliveryMode}</td>
      <td><Badge tone={statusTone(item.status)}>{statusLabel(item)}</Badge></td>
      <td>{canObserve ? <>{observedLabel}{observation?.latestCheckFailed && <small className="muted">（最近查询失败）</small>}</> : '不适用'}</td>
      <td><RecordTime value={observation?.checkedAt} /></td>
      <td>{shortHash(item.subjectHash)}</td><td>{item.deliveryResult ? DELIVERY_LABELS[item.deliveryResult] : '—'}</td><td>{item.attempts}</td><td>{item.benefitOrderNo ?? '—'}</td>
      <td>{blocked ? riskBannerTitle(item.riskAction) : item.riskAction ?? '—'}</td><td>{item.riskReason || (blocked ? '发放指令未进入出站队列。' : '—')}</td><td>{item.riskDecisionId ?? '—'}</td><td>{item.lastError || '—'}</td>
      <td><RecordTime value={item.createdAt} /></td><td><RecordTime value={item.updatedAt} /></td><td><RecordTime value={item.sentAt} /></td>
      <td><div className="record-actions">
        {canObserve && <button className="row-action" type="button" aria-expanded={expanded} aria-controls={`award-fulfillment-${item.intentId}`} onClick={(event) => onObserve(event.currentTarget)}>查看发放结果</button>}
        <button className="row-action" type="button" onClick={() => void navigator.clipboard.writeText(item.sourceRequestId)}>
          <Copy size={13} />复制 sourceRequestId
        </button>
        {orderHref ? (
          <a href={orderHref} target="_blank" rel="noreferrer">
            <ExternalLink size={13} />打开权益订单
          </a>
        ) : riskHref ? (
          <a href={riskHref} target="_blank" rel="noreferrer">
            <ExternalLink size={13} />打开风控决策
          </a>
        ) : item.status === DELIVERY_STATUS.DEAD ? (
          <span>未配置 BENEFIT_CONSOLE_ORIGIN，仅可复制 sourceRequestId</span>
        ) : blocked ? (
          <span>未配置 RISK_CONSOLE_ORIGIN，仅可复制 sourceRequestId</span>
        ) : null}
      </div></td>
    </tr>
  )
}
