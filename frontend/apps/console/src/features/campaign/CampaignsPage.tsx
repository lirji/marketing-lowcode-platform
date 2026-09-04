import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CalendarDays, Filter, Plus, Search } from 'lucide-react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { z } from 'zod'
import { api } from '../../shared/api/client'
import { demoCampaigns } from '../../shared/data/demo'
import type { Campaign } from '../../shared/model/types'
import { Badge, Button, DemoBanner, EmptyState, Modal, PageHeader, Panel, StateBanner } from '../../components/ui'
import { problemDetail } from '../../shared/api/problem'
import { useAuth } from '../../shared/auth/useAuth'

const campaignSchema = z.object({
  name: z.string().trim().min(2, '活动名称至少 2 个字').max(80),
  objective: z.string().trim().min(4, '请描述可衡量的业务目标').max(240),
  organizationId: z.string().trim().min(1, '请选择组织'),
  shopId: z.string().trim().min(1, '请选择店铺范围'),
})
type CampaignInput = z.infer<typeof campaignSchema>

const statusMeta: Record<Campaign['status'], { label: string; tone: 'good'|'warn'|'danger'|'neutral'|'info' }> = {
  DRAFT: { label: '草稿', tone: 'neutral' }, IN_REVIEW: { label: '审核中', tone: 'warn' },
  APPROVED: { label: '已批准', tone: 'info' }, ACTIVE: { label: '运行中', tone: 'good' },
  PAUSED: { label: '已暂停', tone: 'danger' }, ENDED: { label: '已结束', tone: 'neutral' },
}

export function CampaignsPage() {
  const [params, setParams] = useSearchParams()
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState('ALL')
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
    mutationFn: async (payload: CampaignInput) => {
      if (!api.demoMode) return api.createCampaign(payload)
      return { ...payload, id: `CMP-${1100 + localCampaigns.length + 1}`, status: 'DRAFT' as const, createdBy: auth.displayName, createdAt: new Date().toISOString() } satisfies Campaign
    },
    onSuccess: (value) => {
      if (api.demoMode) {
        setLocalCampaigns((items) => [value, ...items])
        void queryClient.invalidateQueries({ queryKey: ['campaigns'] })
        setParams({})
        return
      }
      void queryClient.invalidateQueries({ queryKey: ['campaigns'] })
      setParams({})
      navigate(`/designers/offer?campaignId=${encodeURIComponent(value.id)}`)
    },
  })
  const filtered = useMemo(() => (campaigns.data ?? []).filter((item) => (status === 'ALL' || item.status === status) && `${item.name}${item.objective}${item.id}`.toLowerCase().includes(query.toLowerCase())), [campaigns.data, query, status])
  const orgOptions = auth.organizations.filter((item) => item !== '*')
  const shopOptions = auth.shops.filter((item) => item !== '*')

  return <div className="workspace">
    <PageHeader eyebrow="计划与活动" title="把营销目标组织成可治理的活动。" description="活动统一编排 Offer、Audience、Journey、Experiment 与 Activation，并以 ReleaseBundle 发布。" actions={<><Button disabled title="营销日历尚未接入"><CalendarDays size={16} />营销日历</Button>{auth.hasPermission('campaign:write') && <Button tone="primary" onClick={() => setParams({ create: 'true' })}><Plus size={16} />创建活动</Button>}</>} />
    <DemoBanner />
    <div className="toolbar"><label className="field-search"><Search size={15} /><span className="sr-only">搜索活动</span><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索名称、目标或活动 ID" /></label><label className="compact-select"><Filter size={14} /><span className="sr-only">状态筛选</span><select value={status} onChange={(event) => setStatus(event.target.value)}><option value="ALL">全部状态</option>{Object.entries(statusMeta).map(([value, meta]) => <option value={value} key={value}>{meta.label}</option>)}</select></label><span className="toolbar-count">{filtered.length} 个活动</span></div>
    {campaigns.isError && <StateBanner tone="error" title="活动列表加载失败" detail={problemDetail(campaigns.error)} />}
    <Panel className="table-panel">
      {campaigns.isLoading ? <div className="skeleton-list" aria-label="正在加载活动"><i /><i /><i /></div> : filtered.length === 0 ? <EmptyState title="没有匹配的活动" detail="调整筛选条件，或从模板创建一个新活动。" /> : <div className="data-table campaign-list" role="table" aria-label="营销活动">
        <div className="table-head" role="row"><span role="columnheader">活动</span><span role="columnheader">状态</span><span role="columnheader">负责人与范围</span><span role="columnheader">最近更新</span><span role="columnheader">操作</span></div>
        {filtered.map((item) => <div className="table-row" role="row" key={item.id}><div><strong>{item.name}</strong><small>{item.id} · {item.objective}</small></div><span><Badge tone={statusMeta[item.status].tone}>{statusMeta[item.status].label}</Badge></span><div><strong>{item.createdBy ?? '平台运营'}</strong><small>{item.organizationId ?? orgOptions[0] ?? '—'} / {item.shopId ?? shopOptions[0] ?? '—'}</small></div><span className="mono">{new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', timeZone: 'Asia/Shanghai' }).format(new Date(item.updatedAt ?? item.createdAt))}</span><div className="row-actions"><Link to={`/designers/offer?campaignId=${encodeURIComponent(item.id)}`}>打开</Link></div></div>)}
      </div>}
    </Panel>
    {creationOpen && <CampaignModal organizations={orgOptions} shops={shopOptions} submitting={create.isPending} error={create.error ? problemDetail(create.error) : undefined} onClose={() => setParams({})} onSubmit={(value) => create.mutate(value)} />}
  </div>
}

function CampaignModal({ onClose, onSubmit, submitting, error, organizations, shops }: { onClose: () => void; onSubmit: (value: CampaignInput) => void; submitting: boolean; error?: string; organizations: string[]; shops: string[] }) {
  const orgChoices = organizations.length > 0 ? organizations : ['retail-business']
  const shopChoices = shops.length > 0 ? shops : ['all-shops']
  const [values, setValues] = useState<CampaignInput>({ name: '', objective: '', organizationId: orgChoices[0], shopId: shopChoices[0] })
  const [errors, setErrors] = useState<Record<string, string>>({})
  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    const result = campaignSchema.safeParse(values)
    if (!result.success) { setErrors(Object.fromEntries(result.error.issues.map((issue) => [String(issue.path[0]), issue.message]))); return }
    setErrors({}); onSubmit(result.data)
  }
  return <Modal title="创建营销活动" description="先建立治理边界，随后再组合五类低代码定义。" onClose={onClose}><form className="form-grid" onSubmit={submit}><label className="field span-2"><span>活动名称</span><input autoFocus value={values.name} onChange={(event) => setValues({ ...values, name: event.target.value })} aria-invalid={Boolean(errors.name)} aria-describedby="name-error" placeholder="例如：双11家电主会场" />{errors.name && <small id="name-error">{errors.name}</small>}</label><label className="field span-2"><span>可衡量目标</span><textarea value={values.objective} onChange={(event) => setValues({ ...values, objective: event.target.value })} aria-invalid={Boolean(errors.objective)} placeholder="目标人群、核心动作与期望结果" />{errors.objective && <small>{errors.objective}</small>}</label><label className="field"><span>所属组织</span><select value={values.organizationId} onChange={(event) => setValues({ ...values, organizationId: event.target.value })}>{orgChoices.map((item) => <option value={item} key={item}>{item}</option>)}</select></label><label className="field"><span>店铺范围</span><select value={values.shopId} onChange={(event) => setValues({ ...values, shopId: event.target.value })}>{shopChoices.map((item) => <option value={item} key={item}>{item}</option>)}</select></label>{error && <StateBanner tone="error" title="创建失败" detail={error} />}<footer className="modal-actions span-2"><Button type="button" onClick={onClose}>取消</Button><Button tone="primary" type="submit" disabled={submitting}>{submitting ? '正在创建…' : '创建并进入设计'}</Button></footer></form></Modal>
}
