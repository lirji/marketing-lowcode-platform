import { Fragment, useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Filter, Plus } from 'lucide-react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { z } from 'zod'
import { api } from '../../shared/api/client'
import { demoCampaigns } from '../../shared/data/demo'
import type { Campaign } from '../../shared/model/types'
import { Badge, Button, DemoBanner, EmptyState, Modal, PageHeader, Panel, StateBanner } from '../../components/ui'
import { ActionMenu } from '../../components/ActionMenu'
import { ListToolbar } from '../../components/ListToolbar'
import { CampaignCreateReadiness, CampaignReadiness } from './CampaignReadiness'
import { problemDetail } from '../../shared/api/problem'
import { orgChoicesFromIdentity, shopChoicesFromIdentity } from '../../shared/auth/scopeChoices'
import { useAuth } from '../../shared/auth/useAuth'

const campaignSchema = z.object({
  name: z.string().trim().min(2, '活动名称至少 2 个字').max(80),
  objective: z.string().trim().min(4, '请描述可衡量的业务目标').max(240),
  organizationId: z.string().trim().min(1, '请选择组织'),
  shopId: z.string().trim(),
})
type CampaignInput = z.infer<typeof campaignSchema>
type DesignIntent = 'STANDARD' | 'REFERRAL'

const statusMeta: Record<Campaign['status'], { label: string; tone: 'good'|'warn'|'danger'|'neutral'|'info' }> = {
  DRAFT: { label: '草稿', tone: 'neutral' }, IN_REVIEW: { label: '审核中', tone: 'warn' },
  APPROVED: { label: '已批准', tone: 'info' }, ACTIVE: { label: '运行中', tone: 'good' },
  PAUSED: { label: '已暂停', tone: 'danger' }, ENDED: { label: '已结束', tone: 'neutral' },
}

export function CampaignsPage() {
  const [params, setParams] = useSearchParams()
  const query = params.get('q') ?? ''
  const requestedStatus = params.get('status') ?? 'ALL'
  const status = Object.hasOwn(statusMeta, requestedStatus) ? requestedStatus : 'ALL'
  // 查询保存在URL中，从设计器返回或关闭创建弹窗时保留当前列表上下文。
  const updateParams = (values: Record<string, string | null>) => setParams((current) => {
    const next = new URLSearchParams(current)
    for (const [key, value] of Object.entries(values)) {
      if (value) next.set(key, value)
      else next.delete(key)
    }
    return next
  }, { replace: true })
  const [readyId, setReadyId] = useState<string | null>(null)
  const [localCampaigns, setLocalCampaigns] = useState(demoCampaigns)
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const auth = useAuth()
  const creationOpen = params.get('create') === 'true'
  const campaigns = useQuery({
    queryKey: ['campaigns'], queryFn: api.demoMode ? async () => localCampaigns : api.campaigns,
  })
  useEffect(() => { if (api.demoMode) void queryClient.invalidateQueries({ queryKey: ['campaigns'] }) }, [localCampaigns, queryClient])
  const create = useMutation({
    mutationFn: async (payload: CampaignInput & { designIntent: DesignIntent }) => {
      const { designIntent, ...body } = payload
      if (!api.demoMode) {
        const campaign = await api.createCampaign(body)
        return { campaign, designIntent }
      }
      return {
        campaign: { ...body, id: `CMP-${1100 + localCampaigns.length + 1}`, status: 'DRAFT' as const, createdBy: auth.displayName, createdAt: new Date().toISOString() } satisfies Campaign,
        designIntent,
      }
    },
    onSuccess: ({ campaign: value, designIntent }) => {
      if (api.demoMode) {
        setLocalCampaigns((items) => [value, ...items])
        void queryClient.invalidateQueries({ queryKey: ['campaigns'] })
        updateParams({ create: null })
        return
      }
      queryClient.setQueryData(['campaigns'], (current: Campaign[] | undefined) => {
        if (!current) return [value]
        return current.some((item) => item.id === value.id) ? current : [value, ...current]
      })
      void queryClient.invalidateQueries({ queryKey: ['campaigns'] })
      setParams({})
      const path = designIntent === 'REFERRAL' ? '/designers/referral' : '/designers/offer'
      navigate(`${path}?campaignId=${encodeURIComponent(value.id)}`, { state: { campaignName: value.name } })
    },
  })
  const filtered = useMemo(() => (campaigns.data ?? []).filter((item) => (status === 'ALL' || item.status === status) && `${item.name}${item.objective}${item.id}`.toLowerCase().includes(query.trim().toLowerCase())), [campaigns.data, query, status])
  const orgOptions = orgChoicesFromIdentity(auth.organizations)
  const shopOptions = shopChoicesFromIdentity(auth.shops)

  return <div className="workspace campaigns-page">
    <PageHeader eyebrow="运营工作台" title="计划与活动" description="管理营销活动，查看就绪情况，并进入对应的规则与旅程设计。" actions={auth.hasPermission('campaign:write') && <Button tone="primary" onClick={() => updateParams({ create: 'true' })}><Plus size={16} />创建活动</Button>} />
    <DemoBanner />
    <Panel className="table-panel">
      <ListToolbar query={query} onQueryChange={(q) => updateParams({ q })} searchLabel="搜索活动" placeholder="搜索名称、目标或活动 ID" count={campaigns.data ? filtered.length : undefined} unit="个活动" busy={campaigns.isFetching} onRefresh={() => void campaigns.refetch()} onReset={query || status !== 'ALL' ? () => updateParams({ q: null, status: null }) : undefined}>
        <label className="compact-select"><Filter size={15} aria-hidden="true" /><span className="sr-only">状态筛选</span><select value={status} onChange={(event) => updateParams({ status: event.target.value === 'ALL' ? null : event.target.value })}><option value="ALL">全部状态</option>{Object.entries(statusMeta).map(([value, meta]) => <option value={value} key={value}>{meta.label}</option>)}</select></label>
      </ListToolbar>
      {campaigns.isError && <div className="collection-message"><StateBanner tone="error" title="活动列表加载失败" detail={problemDetail(campaigns.error)} /></div>}
      {campaigns.isLoading ? <div className="skeleton-list" aria-label="正在加载活动"><i /><i /><i /></div> : !campaigns.data && campaigns.isError ? null : filtered.length === 0 ? <EmptyState title={query || status !== 'ALL' ? '没有匹配的活动' : '还没有营销活动'} detail={query || status !== 'ALL' ? '试试其他关键词，或清除筛选查看全部活动。' : '创建一个活动，开始配置营销规则与旅程。'} action={query || status !== 'ALL' ? <Button onClick={() => updateParams({ q: null, status: null })}>清除筛选</Button> : undefined} /> : <div className="table-scroll" role="region" aria-label="营销活动表格" tabIndex={0}>
        <table className="campaign-list" aria-label="营销活动">
          <colgroup><col className="campaign-name-column" /><col className="campaign-status-column" /><col className="campaign-owner-column" /><col className="campaign-date-column" /><col className="campaign-action-column" /></colgroup>
          <thead><tr><th scope="col">活动名称 / 目标</th><th scope="col">状态</th><th scope="col">负责人与范围</th><th scope="col">最近更新</th><th scope="col">操作</th></tr></thead>
          <tbody>{filtered.map((item) => <Fragment key={item.id}>
            <tr className={readyId === item.id ? 'is-expanded' : ''}>
              <td><strong className="record-name">{item.name}</strong><span className="record-description" title={item.objective}>{item.objective}</span><small className="record-meta" title={item.id}>{item.id} · {item.campaignType ? campaignTypeLabel(item.campaignType) : '类型未设置'}</small></td>
              <td><Badge tone={statusMeta[item.status].tone}>{statusMeta[item.status].label}</Badge></td>
              <td><span>{item.createdBy ?? '平台运营'}</span><small className="record-description" title={`${item.organizationId ?? orgOptions[0] ?? '—'} / ${item.shopId || '全部店铺'}`}>{item.organizationId ?? orgOptions[0] ?? '—'} / {item.shopId || '全部店铺'}</small></td>
              <td><time className="record-date" dateTime={item.updatedAt ?? item.createdAt}>{new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', timeZone: 'Asia/Shanghai' }).format(new Date(item.updatedAt ?? item.createdAt))}</time></td>
              <td><div className="row-actions"><button type="button" className="row-action" aria-expanded={readyId === item.id} aria-controls={readyId === item.id ? `readiness-${item.id}` : undefined} onClick={() => setReadyId((current) => current === item.id ? null : item.id)}>{readyId === item.id ? '收起就绪' : '查看就绪'}</button><ActionMenu label="设计" subject={item.name} items={[
                { label: 'Offer 规则', to: `/designers/offer?campaignId=${encodeURIComponent(item.id)}`, state: { campaignName: item.name } },
                { label: 'Journey 旅程', to: `/designers/journey?campaignId=${encodeURIComponent(item.id)}`, state: { campaignName: item.name } },
                { label: '邀请有礼', to: `/designers/referral?campaignId=${encodeURIComponent(item.id)}`, state: { campaignName: item.name } },
              ]} /></div></td>
            </tr>
            {readyId === item.id && <tr className="readiness-row"><td colSpan={5}><div id={`readiness-${item.id}`}><CampaignReadiness campaignId={item.id} /></div></td></tr>}
          </Fragment>)}</tbody>
        </table>
      </div>}
    </Panel>
    {creationOpen && <CampaignModal organizations={orgOptions} shops={shopOptions} submitting={create.isPending} error={create.error ? problemDetail(create.error) : undefined} onClose={() => updateParams({ create: null })} onSubmit={(value, designIntent) => create.mutate({ ...value, designIntent })} />}
  </div>
}

function campaignTypeLabel(value: string): string {
  if (value === 'REFERRAL') return '邀请有礼'
  if (value === 'STANDARD') return '标准营销'
  return '类型待确认'
}

function CampaignModal({ onClose, onSubmit, submitting, error, organizations, shops }: { onClose: () => void; onSubmit: (value: CampaignInput, intent: DesignIntent) => void; submitting: boolean; error?: string; organizations: string[]; shops: { value: string; label: string }[] }) {
  const noOrg = organizations.length === 0
  const [values, setValues] = useState<CampaignInput>({
    name: '',
    objective: '',
    organizationId: organizations[0] ?? '',
    shopId: shops[0]?.value ?? '',
  })
  const [designIntent, setDesignIntent] = useState<DesignIntent>('STANDARD')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    const result = campaignSchema.safeParse(values)
    if (!result.success) { setErrors(Object.fromEntries(result.error.issues.map((issue) => [String(issue.path[0]), issue.message]))); return }
    setErrors({}); onSubmit(result.data, designIntent)
  }
  return <Modal title="创建营销活动" description="先建立治理边界，随后再组合五类低代码定义。" onClose={onClose}><form className="form-grid" onSubmit={submit}><label className="field span-2"><span>活动名称</span><input autoFocus value={values.name} onChange={(event) => setValues({ ...values, name: event.target.value })} aria-invalid={Boolean(errors.name)} aria-describedby="name-error" placeholder="例如：双11家电主会场" />{errors.name && <small id="name-error">{errors.name}</small>}</label><label className="field span-2"><span>可衡量目标</span><textarea value={values.objective} onChange={(event) => setValues({ ...values, objective: event.target.value })} aria-invalid={Boolean(errors.objective)} placeholder="目标人群、核心动作与期望结果" />{errors.objective && <small>{errors.objective}</small>}</label><label className="field"><span>所属组织</span><select value={values.organizationId} onChange={(event) => setValues({ ...values, organizationId: event.target.value })} disabled={noOrg} aria-invalid={noOrg}>{organizations.map((item) => <option value={item} key={item}>{item}</option>)}</select>{noOrg && <small>当前身份没有组织范围，无法创建活动。</small>}</label><label className="field"><span>店铺范围</span><select value={values.shopId} onChange={(event) => setValues({ ...values, shopId: event.target.value })}>{shops.map((item) => <option value={item.value} key={item.value || 'all-scope'}>{item.label}</option>)}</select>{shops.length === 1 && shops[0]?.value === '' && <small>当前身份未声明店铺，将按全部店铺范围创建。</small>}</label><label className="field span-2"><span>创建后进入</span><select value={designIntent} onChange={(event) => setDesignIntent(event.target.value as DesignIntent)}><option value="STANDARD">标准营销 · Offer 设计器</option><option value="REFERRAL">邀请有礼 · 裂变规则设计器</option></select><small>此项只决定跳转，不会写入活动类型。campaignType 待后端合同。</small></label><div className="span-2"><CampaignCreateReadiness designIntent={designIntent} /></div>{error && <StateBanner tone="error" title="创建失败" detail={error} />}<footer className="modal-actions span-2"><Button type="button" onClick={onClose}>取消</Button><Button tone="primary" type="submit" disabled={submitting || noOrg}>{submitting ? '正在创建…' : '创建并进入设计'}</Button></footer></form></Modal>
}
