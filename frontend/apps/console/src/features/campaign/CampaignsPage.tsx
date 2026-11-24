import { CAMPAIGN_TYPE, type CampaignType } from '../../shared/model/campaignType'
import { RecordTable, RecordTime } from '../../components/RecordTable'
import { FieldHint } from '../../components/FieldHint'
import { Select } from '../../components/Select'
import { Fragment, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Filter, Plus } from 'lucide-react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { z } from 'zod'
import { api } from '../../shared/api/client'
import type { Campaign } from '../../shared/model/types'
import { Badge, Button, EmptyState, Modal, PageHeader, Panel, StateBanner } from '../../components/ui'
import { ActionMenu } from '../../components/ActionMenu'
import { ListToolbar } from '../../components/ListToolbar'
import { campaignWorkbenchPath } from './thresholdOffer'
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
type DesignIntent = CampaignType
const CAMPAIGN_WRITE_UNAVAILABLE = '当前模式或权限不支持创建活动，请连接营销服务并确认创建权限。'

const statusMeta: Record<Campaign['status'], { label: string; tone: 'good'|'warn'|'danger'|'neutral'|'info' }> = {
  DRAFT: { label: '草稿', tone: 'neutral' }, IN_REVIEW: { label: '审核中', tone: 'warn' },
  APPROVED: { label: '已批准', tone: 'info' }, ACTIVE: { label: '运行中', tone: 'good' },
  PAUSED: { label: '已暂停', tone: 'danger' }, ENDED: { label: '已结束', tone: 'neutral' },
}

const campaignColumns = [
  { label: '活动名称', width: 154 }, { label: '状态', width: 76 }, { label: '活动类型', width: 94 },
  { label: '目标', width: 190 }, { label: '活动 ID', width: 150 }, { label: '创建人', width: 96 },
  { label: '组织 ID', width: 110 }, { label: '店铺 ID', width: 105 },
  { label: '创建时间（北京时间）', width: 146 }, { label: '更新时间（北京时间）', width: 146 },
  { label: '操作', width: 148 },
] as const

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
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const auth = useAuth()
  const canRead = !api.demoMode && auth.authenticated && auth.hasPermission('campaign:read')
  const canWrite = canRead && auth.hasPermission('campaign:write')
  const campaignKey = ['campaigns', auth.tenantId, auth.subject, auth.organizations, auth.shops, auth.permissions]
  const canUseTemplate = canWrite && auth.hasPermission('definition:read') && auth.hasPermission('definition:write')
  const fromTemplate = params.get('create') === 'threshold'
  const creationOpen = params.get('create') === 'true' || (fromTemplate && canUseTemplate)
  const campaigns = useQuery({
    queryKey: campaignKey, queryFn: api.campaigns, enabled: canRead,
  })
  const create = useMutation({
    mutationFn: async (payload: CampaignInput & { designIntent: DesignIntent; fromTemplate: boolean }) => {
      if (!canWrite) throw new Error(CAMPAIGN_WRITE_UNAVAILABLE)
      const { designIntent, fromTemplate, ...body } = payload
      const campaign = await api.createCampaign({ ...body, campaignType: designIntent })
      return { campaign, fromTemplate }
    },
    onSuccess: ({ campaign: value, fromTemplate }) => {
      queryClient.setQueryData(campaignKey, (current: Campaign[] | undefined) => {
        if (!current) return [value]
        return current.some((item) => item.id === value.id) ? current : [value, ...current]
      })
      void queryClient.invalidateQueries({ queryKey: ['campaigns'] })
      setParams({})
      if (fromTemplate) { navigate(campaignWorkbenchPath(value.id)); return }
      const path = value.campaignType === CAMPAIGN_TYPE.REFERRAL ? '/designers/referral' : '/designers/offer'
      navigate(`${path}?campaignId=${encodeURIComponent(value.id)}`, { state: { campaignName: value.name } })
    },
  })
  const filtered = useMemo(() => (campaigns.data ?? []).filter((item) => (status === 'ALL' || item.status === status) && `${item.name}${item.objective}${item.id}`.toLowerCase().includes(query.trim().toLowerCase())), [campaigns.data, query, status])
  const orgOptions = orgChoicesFromIdentity(auth.organizations)
  const shopOptions = shopChoicesFromIdentity(auth.shops)

  return <div className="workspace campaigns-page">
    <PageHeader title="计划与活动" description="查看活动状态、规则配置和经营报表。" actions={auth.hasPermission('campaign:write') && <>
      {canUseTemplate && <Button onClick={() => updateParams({ create: 'threshold' })}>从满减模板创建</Button>}
      <Button tone="primary" disabled={!canWrite} title={canWrite ? undefined : CAMPAIGN_WRITE_UNAVAILABLE} onClick={() => updateParams({ create: 'true' })}><Plus size={16} />创建活动</Button></>} />
    <Panel className="table-panel">
      {api.demoMode ? <EmptyState title="活动列表需要连接后端" detail="当前为静态预览。连接营销服务后，可查看已保存的活动并创建新活动。" /> : !canRead ? <StateBanner tone="warn" title="没有查看活动的权限" detail="需要有效身份与 campaign:read 权限。" /> : <>
      <ListToolbar query={query} onQueryChange={(q) => updateParams({ q })} searchLabel="搜索活动" describedBy="campaign-search-hint" placeholder="搜索名称、目标或活动 ID" count={campaigns.data ? filtered.length : undefined} unit="个活动" busy={campaigns.isFetching} onRefresh={() => void campaigns.refetch()} onReset={query || status !== 'ALL' ? () => updateParams({ q: null, status: null }) : undefined}>
        <label className="compact-select"><Filter size={15} aria-hidden="true" /><span className="sr-only">状态筛选</span><Select value={status} onChange={(event) => updateParams({ status: event.target.value === 'ALL' ? null : event.target.value })}><option value="ALL">全部状态</option>{Object.entries(statusMeta).map(([value, meta]) => <option value={value} key={value}>{meta.label}</option>)}</Select></label>
      </ListToolbar>
      <div className="collection-search-help campaign-list-help">
        <FieldHint id="campaign-search-hint">支持按活动名称、活动 ID 或目标搜索。</FieldHint>
        {filtered.length > 0 && <span className="field-hint campaign-scroll-hint">横向滚动可查看完整字段</span>}
      </div>
      {campaigns.isError && <div className="collection-message"><StateBanner tone="error" title="活动列表加载失败" detail={problemDetail(campaigns.error)} /></div>}
      {campaigns.isLoading ? <div className="skeleton-list" aria-label="正在加载活动"><i /><i /><i /></div> : !campaigns.data && campaigns.isError ? null : filtered.length === 0 ? <EmptyState title={query || status !== 'ALL' ? '没有匹配的活动' : '还没有营销活动'} detail={query || status !== 'ALL' ? '试试其他关键词，或清除筛选查看全部活动。' : '创建一个活动，开始配置营销规则与旅程。'} action={query || status !== 'ALL' ? <Button onClick={() => updateParams({ q: null, status: null })}>清除筛选</Button> : undefined} /> : <RecordTable label="营销活动" columns={campaignColumns} actions>
          {filtered.map((item) => <Fragment key={item.id}>
            <tr className={readyId === item.id ? 'is-expanded' : ''}>
              <td>{item.campaignType === CAMPAIGN_TYPE.STANDARD ? <Link className="record-name campaign-workbench-link" to={campaignWorkbenchPath(item.id)}>{item.name}</Link> : <strong className="record-name">{item.name}</strong>}</td>
              <td><Badge tone={statusMeta[item.status].tone}>{statusMeta[item.status].label}</Badge></td>
              <td>{item.campaignType ? campaignTypeLabel(item.campaignType) : '类型未设置'}</td>
              <td className="campaign-objective-cell">{item.objective || '未填写目标'}</td>
              <td className="campaign-id-cell">{item.id}</td>
              <td>{item.createdBy ?? '未提供'}</td>
              <td>{item.organizationId ?? '未提供'}</td>
              <td>{item.shopId === undefined ? '未提供' : item.shopId || '全部店铺'}</td>
              <td><RecordTime value={item.createdAt} /></td>
              <td><RecordTime value={item.updatedAt} /></td>
              <td><div className="row-actions">
                {auth.hasPermission('measurement:read') && <Link className="row-action" to={`/analytics?campaignId=${encodeURIComponent(item.id)}`} aria-label={`查看${item.name}的报表`}>查看报表</Link>}
                <button type="button" className="row-action" aria-expanded={readyId === item.id} aria-controls={readyId === item.id ? `readiness-${item.id}` : undefined} onClick={() => setReadyId((current) => current === item.id ? null : item.id)}>{readyId === item.id ? '收起就绪' : '查看就绪'}</button>
                <ActionMenu label="设计" subject={item.name} items={item.campaignType === CAMPAIGN_TYPE.REFERRAL ? [
                { label: '邀请有礼', to: `/designers/referral?campaignId=${encodeURIComponent(item.id)}`, state: { campaignName: item.name } },
              ] : [
                { label: 'Offer 规则', to: `/designers/offer?campaignId=${encodeURIComponent(item.id)}`, state: { campaignName: item.name } },
                { label: 'Journey 旅程', to: `/designers/journey?campaignId=${encodeURIComponent(item.id)}`, state: { campaignName: item.name } },
                ]} />
                {auth.hasPermission('release:read') && item.campaignType === CAMPAIGN_TYPE.STANDARD && <button type="button" className="row-action" onClick={() => navigate(`/releases/bundles?campaignId=${encodeURIComponent(item.id)}`)}>组合发布</button>}
              </div></td>
            </tr>
            {readyId === item.id && <tr className="readiness-row"><td colSpan={11}><div id={`readiness-${item.id}`}><CampaignReadiness campaignId={item.id} campaignType={item.campaignType} /></div></td></tr>}
          </Fragment>)}
      </RecordTable>}
    </>}
    </Panel>
    {creationOpen && canWrite && <CampaignModal key={String(fromTemplate)} fromTemplate={fromTemplate} organizations={orgOptions} shops={shopOptions} submitting={create.isPending} error={create.error ? problemDetail(create.error) : undefined} onClose={() => updateParams({ create: null })} onSubmit={(value, designIntent) => create.mutate({ ...value, designIntent, fromTemplate })} />}
  </div>
}

function campaignTypeLabel(value: string): string {
  if (value === CAMPAIGN_TYPE.REFERRAL) return '邀请有礼'
  if (value === CAMPAIGN_TYPE.STANDARD) return '标准营销'
  return '类型待确认'
}

function CampaignModal({ onClose, onSubmit, submitting, error, organizations, shops, fromTemplate }: { fromTemplate: boolean; onClose: () => void; onSubmit: (value: CampaignInput, intent: DesignIntent) => void; submitting: boolean; error?: string; organizations: string[]; shops: { value: string; label: string }[] }) {
  const noOrg = organizations.length === 0
  const [values, setValues] = useState<CampaignInput>({
    name: '',
    objective: '',
    organizationId: organizations[0] ?? '',
    shopId: shops[0]?.value ?? '',
  })
  const [designIntent, setDesignIntent] = useState<DesignIntent>(CAMPAIGN_TYPE.STANDARD)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    const result = campaignSchema.safeParse(values)
    if (fromTemplate && !values.shopId.trim()) { setErrors({ shopId: '请选择或填写指定门店 ID' }); return }
    if (!result.success) { setErrors(Object.fromEntries(result.error.issues.map((issue) => [String(issue.path[0]), issue.message]))); return }
    setErrors({}); onSubmit(result.data, designIntent)
  }
  return <Modal title={fromTemplate ? '从满减模板创建活动' : '创建营销活动'} description={fromTemplate ? '先确定活动归属，再在工作台填写满减规则。' : '选择活动类型与治理范围，再配置相应规则。'} onClose={onClose}><form className="form-grid" onSubmit={submit}><label className="field span-2"><span>活动名称</span><input autoFocus aria-label="活动名称" value={values.name} onChange={(event) => setValues({ ...values, name: event.target.value })} aria-invalid={Boolean(errors.name)} aria-describedby="name-hint name-error" placeholder="例如：双11家电主会场" /><FieldHint id="name-hint">填写便于识别和搜索的业务名称，2–80 字。</FieldHint>{errors.name && <small id="name-error">{errors.name}</small>}</label><label className="field span-2"><span>可衡量目标</span><textarea aria-label="可衡量目标" value={values.objective} onChange={(event) => setValues({ ...values, objective: event.target.value })} aria-invalid={Boolean(errors.objective)} aria-describedby="objective-hint" placeholder="目标人群、核心动作与期望结果" /><FieldHint id="objective-hint">说明面向谁、希望完成什么动作及预期结果，4–240 字；列表会展示此目标。</FieldHint>{errors.objective && <small>{errors.objective}</small>}</label><label className="field"><span>所属组织</span><Select value={values.organizationId} onChange={(event) => setValues({ ...values, organizationId: event.target.value })} disabled={noOrg} aria-invalid={noOrg}>{organizations.map((item) => <option value={item} key={item}>{item}</option>)}</Select>{noOrg && <small>当前身份没有组织范围，无法创建活动。</small>}</label><label className="field"><span>店铺范围</span>{fromTemplate && shops[0]?.value === ''
      ? <input value={values.shopId} required maxLength={128} aria-label="指定门店 ID" aria-describedby="template-shop-hint" onChange={event => setValues({ ...values, shopId: event.target.value })} />
      : <Select value={values.shopId} onChange={(event) => setValues({ ...values, shopId: event.target.value })}>{shops.map((item) => <option value={item.value} key={item.value || 'all-scope'}>{item.label}</option>)}</Select>}
    {fromTemplate ? <FieldHint id="template-shop-hint">模板仅适用于一个门店，使用交易系统的门店 ID。</FieldHint> : shops.length === 1 && shops[0]?.value === '' && <small>当前身份未声明店铺，将按全部店铺范围创建。</small>}
    {errors.shopId && <small>{errors.shopId}</small>}</label>{!fromTemplate && <><label className="field span-2"><span>活动类型</span><Select aria-label="活动类型" aria-describedby="campaign-type-hint" value={designIntent} onChange={(event) => setDesignIntent(event.target.value as DesignIntent)}><option value="STANDARD">标准营销 · Offer 设计器</option><option value="REFERRAL">邀请有礼 · 裂变规则设计器</option></Select><FieldHint id="campaign-type-hint">活动类型将保存到控制面；标准营销使用 Offer / Journey，邀请有礼使用裂变规则，创建后不可更改。</FieldHint></label><div className="span-2"><CampaignCreateReadiness designIntent={designIntent} /></div></>}{error && <StateBanner tone="error" title="创建失败" detail={error} />}<footer className="action-bar action-bar--end modal-actions span-2"><Button type="button" onClick={onClose}>取消</Button><Button tone="primary" type="submit" disabled={submitting || noOrg}>{submitting ? '正在创建…' : fromTemplate ? '创建并进入工作台' : '创建并进入设计'}</Button></footer></form></Modal>
}
