import { useSearchParams } from 'react-router-dom'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { Copy, ExternalLink } from 'lucide-react'
import { Badge, Button, EmptyState, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { api } from '../../shared/api/client'
import { problemDetail } from '../../shared/api/problem'
import { useAuth } from '../../shared/auth/useAuth'
import { runtimeConfig } from '../../shared/config/runtime'
import type { AwardIntentView } from '../../shared/api/schemas'

const PAGE_SIZE = 20

function statusTone(status: AwardIntentView['status']) {
  if (status === 'SENT') return 'good' as const
  if (status === 'DEAD' || status === 'RISK_BLOCKED') return 'danger' as const
  return 'warn' as const
}

function statusLabel(status: AwardIntentView['status']) {
  if (status === 'RISK_BLOCKED') return '风控拦截'
  return status
}

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

export function benefitOrderHref(item: AwardIntentView, origin = runtimeConfig.benefitConsoleOrigin) {
  if (!origin) return ''
  if (item.benefitOrderNo) {
    return `${origin}/orders?orderNo=${encodeURIComponent(item.benefitOrderNo)}`
  }
  if (item.status === 'DEAD') {
    const params = new URLSearchParams({ q: item.sourceRequestId, sourceSystem: item.sourceSystem })
    return `${origin}/orders?${params}`
  }
  return ''
}

export function riskDecisionHref(item: AwardIntentView, origin = runtimeConfig.riskConsoleOrigin) {
  if (!origin || item.status !== 'RISK_BLOCKED') return ''
  return `${origin}/decisions?q=${encodeURIComponent(item.sourceRequestId)}`
}

export function AwardIntentsPanel() {
  const auth = useAuth()
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
    const copy = new URLSearchParams(params)
    copy.set('tab', 'awards')
    if (next) copy.set('campaignId', next)
    else copy.delete('campaignId')
    setParams(copy, { replace: true })
  }

  return (
    <div id="tab-发放" role="tabpanel">
      <Panel>
        <PanelHeader eyebrow="AWARD INTENTS" title="发放指令" aside={<Badge tone="info">只读</Badge>} />
        {api.demoMode && <StateBanner tone="info" title="演示模式不查询真实发放指令" detail="关闭 DEMO_MODE 后按活动读取 GET /api/v1/award-intents。" />}
        {!api.demoMode && (
          <label className="field award-campaign-field">
            <span>活动</span>
            <select aria-label="活动" value={campaignId} onChange={(event) => selectCampaign(event.target.value)} disabled={campaigns.isPending}>
              <option value="">请选择活动</option>
              {campaignId && !selectedInList && <option value={campaignId}>{campaignId}</option>}
              {(campaigns.data ?? []).map((item) => (
                <option key={item.id} value={item.id}>{item.name} · {item.id}</option>
              ))}
            </select>
          </label>
        )}
        {campaigns.isError && <StateBanner tone="error" title="活动列表加载失败" detail={problemDetail(campaigns.error)} />}
        {intents.isError && <StateBanner tone="error" title="发放指令加载失败" detail={problemDetail(intents.error)} />}
        {!api.demoMode && !campaignId && <EmptyState title="请先选择活动" detail="发放列表必须带 campaignId，未选择时不会请求。" />}
        {!api.demoMode && campaignId && !intents.isPending && rows.length === 0 && !intents.isError && (
          <EmptyState title="本活动尚无发放指令" detail="GET /api/v1/award-intents 返回空列表。" />
        )}
        {rows.length > 0 && (
          <div className="award-intent-list">
            {rows.map((item) => <AwardIntentCard key={item.intentId} item={item} />)}
          </div>
        )}
        {intents.hasNextPage && (
          <div className="award-intent-more">
            <Button onClick={() => void intents.fetchNextPage()} disabled={intents.isFetchingNextPage}>加载更多</Button>
          </div>
        )}
      </Panel>
    </div>
  )
}

function AwardIntentCard({ item }: { item: AwardIntentView }) {
  const orderHref = benefitOrderHref(item)
  const riskHref = riskDecisionHref(item)
  const blocked = item.status === 'RISK_BLOCKED'
  return (
    <article className="award-intent-card">
      <header>
        <div>
          <strong>{item.sourceRequestId}</strong>
          <small>{item.intentId} · {item.deliveryMode} · v{item.definitionVersion}</small>
        </div>
        <Badge tone={statusTone(item.status)}>{statusLabel(item.status)}</Badge>
      </header>
      <dl className="award-intent-meta">
        <div><dt>主体摘要</dt><dd className="mono">{shortHash(item.subjectHash)}</dd></div>
        <div><dt>投递结果</dt><dd>{item.deliveryResult ?? '—'}</dd></div>
        <div><dt>尝试</dt><dd>{item.attempts}</dd></div>
        <div><dt>更新</dt><dd>{item.updatedAt}</dd></div>
      </dl>
      {item.status === 'DEAD' && item.lastError && <StateBanner tone="error" title="投递失败" detail={item.lastError} />}
      {blocked && (
        <StateBanner tone="warn" title={riskBannerTitle(item.riskAction)} detail={item.riskReason || '发放指令未进入出站队列。'} />
      )}
      <footer>
        <button type="button" onClick={() => void navigator.clipboard.writeText(item.sourceRequestId)}>
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
        ) : item.status === 'DEAD' ? (
          <span>未配置权益台地址，仅可复制 sourceRequestId</span>
        ) : blocked ? (
          <span>未配置风控台地址，仅可复制 sourceRequestId</span>
        ) : null}
      </footer>
    </article>
  )
}
