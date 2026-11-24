import { useRef, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { DateTimePicker } from '../../components/DateTimePicker'
import { displayTimestamp } from '../../components/dateTime'
import { FieldHint } from '../../components/FieldHint'
import { Select } from '../../components/Select'
import { Badge, Button, EmptyState, PageHeader, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { api, isDefinitionNotFound } from '../../shared/api/client'
import { problemDetail } from '../../shared/api/problem'
import { isReferralSimulation, type Campaign, type DefinitionBundle, type GraphDefinition } from '../../shared/api/schemas'
import { releaseScopeKey } from '../../shared/api/useReleasePages'
import { useAuth } from '../../shared/auth/useAuth'
import { CAMPAIGN_TYPE } from '../../shared/model/campaignType'
import { CNY, OFFER_DIALECT, THRESHOLD_TEMPLATE, activeCampaignBudget, minorFromYuan, thresholdGraph } from './thresholdOffer'

const DefinitionStatus = {
  DRAFT: 'DRAFT', VALIDATED: 'VALIDATED', IN_REVIEW: 'IN_REVIEW',
  APPROVED: 'APPROVED', RELEASED: 'RELEASED', REJECTED: 'REJECTED',
} as const
const statusLabels: Record<string, string> = {
  [DefinitionStatus.DRAFT]: '草稿', [DefinitionStatus.VALIDATED]: '已校验',
  [DefinitionStatus.IN_REVIEW]: '审核中', [DefinitionStatus.APPROVED]: '已批准',
  [DefinitionStatus.RELEASED]: '已发布', [DefinitionStatus.REJECTED]: '已驳回',
}
const ACTIVE_BENEFIT_STATUS = 'ACTIVE'
const outcomes: Record<string, string> = { APPLIED: '命中满减', THRESHOLD_NOT_MET: '未达到门槛', PRODUCT_SCOPE_MISMATCH: '商品或门店不在范围内', OUTSIDE_VALIDITY: '订单时间不在活动有效期内', NOT_APPLIED: '未应用优惠' }
const yuan = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: CNY })
const dateNow = () => new Date().toISOString()
const ONE_WEEK_MS = 7 * 24 * 60 * 60 * 1_000

async function latestOffer(campaignId: string) {
  try {
    const result = await api.latestDefinition(campaignId, OFFER_DIALECT)
    if (result.campaignId !== campaignId || result.dialect !== OFFER_DIALECT) throw new Error('规则回执与当前活动不一致。')
    return result
  } catch (error) {
    if (isDefinitionNotFound(error)) return null
    throw error
  }
}

export function CampaignWorkbench() {
  const { campaignId = '' } = useParams()
  const auth = useAuth()
  const scope = releaseScopeKey(auth)
  const campaigns = useQuery({ queryKey: ['campaigns', scope], queryFn: api.campaigns, enabled: !api.demoMode })
  const campaign = campaigns.data?.find(item => item.id === campaignId)
  return <div className="workspace campaign-workbench">
    <PageHeader title={campaign?.name ?? '活动工作台'} description={campaign?.objective ?? '配置满减，试跑订单并跟踪当前版本。'} actions={<Link className="button secondary" to="/campaigns">返回计划与活动</Link>} />
    {api.demoMode ? <StateBanner tone="info" title="活动工作台需要连接服务" detail="连接营销服务后读取已保存的活动和规则。" />
      : campaigns.isPending ? <StateBanner tone="loading" title="正在读取活动" detail="正在核对当前身份可见的活动范围。" />
      : campaigns.isError ? <StateBanner tone="error" title="活动加载失败" detail={problemDetail(campaigns.error)} action={<Button onClick={() => void campaigns.refetch()}>重试</Button>} />
      : !campaign || campaign.campaignType !== CAMPAIGN_TYPE.STANDARD ? <EmptyState title="当前活动不可用" detail="此入口用于当前身份可见的标准营销活动。" />
      : <Workbench key={`${scope}:${campaign.id}`} campaign={campaign} scope={scope} />}
  </div>
}

function Workbench({ campaign, scope }: { campaign: Campaign; scope: string }) {
  const auth = useAuth()
  const canRead = auth.hasPermission('definition:read')
  const canWrite = canRead && auth.hasPermission('definition:write')
  const canSubmit = canRead && auth.hasPermission('definition:submit')
  const client = useQueryClient()
  const key = ['campaign-workbench', scope, campaign.id]
  const offer = useQuery({ queryKey: key, queryFn: () => latestOffer(campaign.id), enabled: canRead })
  const definition = offer.isSuccess ? offer.data : undefined
  const afterChange = async () => { await client.invalidateQueries({ queryKey: key, exact: true }) }
  const validate = useMutation({ mutationFn: (target: DefinitionBundle) => api.validate(target.definitionId, target.version), onSettled: afterChange })
  const submit = useMutation({ mutationFn: (target: DefinitionBundle) => api.submit(target.definitionId, target.version), onSettled: afterChange })
  const currentValidation = definition && validate.variables?.definitionId === definition.definitionId && validate.variables.version === definition.version ? validate.data : undefined
  const currentApproval = definition && submit.variables?.definitionId === definition.definitionId && submit.variables.version === definition.version ? submit.data : undefined
  const busy = validate.isPending || submit.isPending || offer.isFetching
  const findNext = campaign.shopId ? '先填写商品、门槛、优惠金额与资金账户。' : '从满减模板创建时需要指定一个门店。现有活动可继续使用 Offer 设计器。'
  const campaignQuery = `campaignId=${encodeURIComponent(campaign.id)}`
  const templateNodes = definition?.graph?.annotations.template === THRESHOLD_TEMPLATE
    ? definition.graph.nodes.filter(node => node.stableTypeId === 'offer.fixed' || node.stableTypeId === 'offer.percentage') : []
  const config = templateNodes.length === 1 && templateNodes[0].stableTypeId === 'offer.fixed' ? templateNodes[0].config : undefined
  return <>
    <Panel>
      <PanelHeader title="活动范围" aside={<Badge tone={definition?.status === DefinitionStatus.RELEASED ? 'good' : 'neutral'}>{definition ? statusLabels[definition.status] ?? definition.status : '规则待确认'}</Badge>} />
      <dl className="workbench-facts"><div><dt>活动 ID</dt><dd>{campaign.id}</dd></div><div><dt>组织</dt><dd>{campaign.organizationId || '未声明'}</dd></div><div><dt>门店</dt><dd>{campaign.shopId || '全部门店'}</dd></div></dl>
    </Panel>
    {!canRead ? <StateBanner tone="warn" title="无规则读取权限" detail="查看 Offer 规则及其版本需要 definition:read 权限。" />
      : offer.isError ? <StateBanner tone="error" title="Offer 读取失败" detail={problemDetail(offer.error)} action={<Button onClick={() => void offer.refetch()}>重试</Button>} />
      : offer.isPending ? <StateBanner tone="loading" title="正在读取规则" detail="获得服务端版本后可以继续操作。" />
      : definition ? <Panel>
        <PanelHeader title={`Offer 规则 v${definition.version}`} aside={<Link className="button secondary" to={`/designers/offer?${campaignQuery}`}>打开 Offer 设计器</Link>} />
        {config && <dl className="workbench-facts">
          <div><dt>商品 SKU ID</dt><dd>{config.skuIds}</dd></div>
          <div><dt>参与商品金额门槛</dt><dd>{yuan.format(Number(config.thresholdMinor) / 100)}</dd></div>
          <div><dt>单次减免</dt><dd>{yuan.format(Number(config.amountMinor) / 100)}</dd></div>
          <div><dt>权益版本</dt><dd>{config.benefitDefinitionVersion}</dd></div>
          <div><dt>生效时间</dt><dd>{displayTimestamp(config.validFrom)}</dd></div>
          <div><dt>失效时间</dt><dd>{displayTimestamp(config.validTo)}</dd></div>
        </dl>}
        {currentValidation && <StateBanner tone={currentValidation.valid ? 'success' : 'warn'} title={currentValidation.valid ? '当前版本校验通过' : '当前版本校验未通过'} detail={currentValidation.issues.map(issue => issue.message).join('。') || `v${definition.version}`} />}
        {currentApproval && <StateBanner tone="success" title="已提交规则审核" detail={`审核单 ${currentApproval.caseId}`} />}
        {(validate.isError || submit.isError) && <StateBanner tone="error" title="操作失败" detail={problemDetail(validate.error ?? submit.error)} />}
        {(canWrite || canSubmit) && <div className="workbench-actions">
          {canWrite && <Button disabled={busy || definition.status !== DefinitionStatus.DRAFT} onClick={() => { submit.reset(); validate.mutate(definition) }}>校验当前版本</Button>}
          {canSubmit && <Button tone="primary" disabled={busy || definition.status !== DefinitionStatus.VALIDATED} onClick={() => { validate.reset(); submit.mutate(definition) }}>提交审核</Button>}
        </div>}
      </Panel> : definition === null && campaign.shopId && canWrite
        ? <ThresholdTemplate campaign={campaign} scope={scope} onSaved={afterChange} />
        : <Panel><EmptyState title="此活动尚无 Offer 规则" detail={findNext} action={canWrite && <Link className="button secondary" to={`/designers/offer?${campaignQuery}`}>打开 Offer 设计器</Link>} /></Panel>}
    {definition && config && auth.hasPermission('definition:simulate') && <OfferTrial key={`${definition.definitionId}:${definition.version}`} definition={definition} config={config} />}
    <Panel>
      <PanelHeader title="审核与发布" />
      <p className="field-hint">规则批准后，在发布中心编译和暂存指定版本。活动工作台保存规则后可在这里继续跟踪。</p>
      <div className="workbench-actions">
        {canRead && <Link className="button secondary" to="/governance">查看审核</Link>}
        {auth.hasPermission('release:read') && <><Link className="button secondary" to="/releases">打开发布中心</Link><Link className="button secondary" to={`/releases/bundles?${campaignQuery}`}>查看活动组合发布</Link></>}
        {auth.hasPermission('measurement:read') && <Link className="button secondary" to={`/analytics?${campaignQuery}`}>查看活动报表</Link>}
      </div>
    </Panel>
  </>
}

function ThresholdTemplate({ campaign, scope, onSaved }: { campaign: Campaign; scope: string; onSaved: () => Promise<void> }) {
  const auth = useAuth()
  const canReadBenefits = auth.hasPermission('benefit:read')
  const canReadFunding = auth.hasPermission('funding:account-read')
  const benefits = useQuery({ queryKey: ['workbench-benefits', scope], queryFn: api.benefits, enabled: canReadBenefits })
  const accounts = useQuery({ queryKey: ['workbench-budgets', scope], queryFn: api.fundingAccounts, enabled: canReadFunding })
  const [form, setForm] = useState(() => ({ skuId: '', benefit: '', budgetKey: '', threshold: '500', discount: '50', validFrom: dateNow(), validTo: new Date(Date.now() + ONE_WEEK_MS).toISOString() }))
  const set = (field: keyof typeof form, value: string) => setForm(current => ({ ...current, [field]: value }))
  const availableBenefits = benefits.isSuccess ? benefits.data.filter(item => item.status === ACTIVE_BENEFIT_STATUS && item.benefitSkuId) : []
  const budgets = accounts.isSuccess ? accounts.data.filter(activeCampaignBudget) : []
  const selectedBudget = budgets.find(item => item.resourceKey === form.budgetKey)
  const graph = thresholdGraph({ ...form, campaignId: campaign.id, shopId: campaign.shopId ?? '', skuId: form.skuId.trim() })
  const valid = graph && selectedBudget && availableBenefits.some(item => `${item.benefitId}@${item.version}` === form.benefit)
  const attempt = useRef<{ hash: string; key: string } | undefined>(undefined)
  const save = useMutation({ mutationFn: ({ graph, key }: { graph: GraphDefinition; key: string }) => api.initializeOffer({ campaignId: campaign.id, graph }, key),
    onSettled: onSaved,
  })
  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (!graph || !valid || save.isPending) return
    const hash = JSON.stringify(graph)
    if (attempt.current?.hash !== hash) attempt.current = { hash, key: crypto.randomUUID() }
    save.mutate({ graph, key: attempt.current.key })
  }
  return <Panel>
    <PanelHeader title="指定门店 SKU 满减" aside={<Badge tone="info">场景模板</Badge>} />
    <form className="form-grid" onSubmit={handleSubmit}>
      <label className="field span-2"><span>商品 SKU ID</span><input aria-label="商品 SKU ID" value={form.skuId} required maxLength={128} aria-describedby="workbench-sku-help" onChange={event => set('skuId', event.target.value)} /><FieldHint id="workbench-sku-help">填写交易系统的商品 SKU ID。只有此门店的指定商品参与门槛和优惠分摊。</FieldHint></label>
      <label className="field"><span>满减门槛（元）</span><input value={form.threshold} required inputMode="decimal" aria-describedby="workbench-threshold-help" onChange={event => set('threshold', event.target.value)} /><FieldHint id="workbench-threshold-help">参与商品金额达到此值时进入优惠计算，最多两位小数。</FieldHint></label>
      <label className="field"><span>减免金额（元）</span><input value={form.discount} required inputMode="decimal" aria-describedby="workbench-discount-help" onChange={event => set('discount', event.target.value)} /><FieldHint id="workbench-discount-help">每次定价最多应用一次，减免金额须大于零且不高于门槛。</FieldHint></label>
      <div className="field"><span>生效时间</span><DateTimePicker label="生效时间" value={form.validFrom} onChange={value => set('validFrom', value)} /></div>
      <div className="field"><span>失效时间</span><DateTimePicker label="失效时间" value={form.validTo} onChange={value => set('validTo', value)} describedBy="workbench-validity-help" /><FieldHint id="workbench-validity-help">选择晚于生效时间的结束时间，结束时刻起停止命中。</FieldHint></div>
      <label className="field"><span>权益定义版本</span><Select aria-label="权益定义版本" aria-describedby="workbench-benefit-help" value={form.benefit} disabled={!availableBenefits.length} onChange={event => set('benefit', event.target.value)}><option value="">请选择</option>{availableBenefits.map(item => <option key={item.benefitId} value={`${item.benefitId}@${item.version}`}>{item.name} · v{item.version}</option>)}</Select><FieldHint id="workbench-benefit-help">选择已激活且绑定权益 SKU 的定义，正式发布时会重验权益。</FieldHint></label>
      <label className="field"><span>出资预算账户</span><Select aria-label="出资预算账户" aria-describedby="workbench-funding-help" value={form.budgetKey} disabled={!budgets.length} onChange={event => set('budgetKey', event.target.value)}><option value="">请选择</option>{budgets.map(item => <option key={item.resourceKey} value={item.resourceKey}>{item.resourceKey}</option>)}</Select><FieldHint id="workbench-funding-help">此账户承担全部优惠，保存草稿不会预占预算。</FieldHint></label>
      {selectedBudget && <p className="field-hint span-2">预算当前可用 {yuan.format(selectedBudget.available / 100)}{selectedBudget.available < Number(minorFromYuan(form.discount) ?? 0) ? '，低于一次减免额。' : '。'}</p>}
      {(!canReadBenefits || !canReadFunding) && <div className="span-2"><StateBanner tone="warn" title="缺少权益或预算账户读取权限" detail="请由具备 benefit:read 和 funding:account-read 的运营角色配置模板。" /></div>}
      {(benefits.isError || accounts.isError) && <div className="span-2"><StateBanner tone="error" title="权益或预算读取失败" detail={problemDetail(benefits.error ?? accounts.error)} action={<Button onClick={() => { if (benefits.isError) void benefits.refetch(); if (accounts.isError) void accounts.refetch() }}>重试</Button>} /></div>}
      {canReadBenefits && canReadFunding && benefits.isSuccess && accounts.isSuccess && (!availableBenefits.length || !budgets.length) && <div className="span-2"><StateBanner tone="warn" title="尚缺可用权益或预算账户" detail="请先在权益与资金中准备已激活的权益版本和 CNY 出资账户。" /></div>}
      {save.isError && <div className="span-2"><StateBanner tone="error" title="满减规则保存失败" detail={problemDetail(save.error)} /></div>}
      <div className="span-2 workbench-actions"><Button tone="primary" type="submit" disabled={!valid || save.isPending}>{save.isPending ? '正在保存…' : '保存满减规则'}</Button></div>
    </form>
  </Panel>
}

function OfferTrial({ definition, config }: { definition: DefinitionBundle; config: Record<string, string> }) {
  const [amount, setAmount] = useState(config.thresholdMinor ? (Number(config.thresholdMinor) / 100).toFixed(2) : '')
  const [sku, setSku] = useState(config.skuIds ?? '')
  const [shop, setShop] = useState(config.shopIds ?? '')
  const [at, setAt] = useState(config.validFrom || dateNow)
  const trial = useMutation({ mutationFn: async (facts: Record<string, string>) => {
    const result = await api.simulate(definition.definitionId, definition.version, facts)
    if (isReferralSimulation(result) || result.trace[0]?.nodeType !== 'product-preview') throw new Error('当前服务未确认商品试跑结果。')
    return result
  } })
  const facts = { amountMinor: minorFromYuan(amount) ?? '', skuId: sku.trim(), shopId: shop.trim(), occurredAt: at, currency: CNY }
  const current = trial.variables && Object.entries(facts).every(([key, value]) => trial.variables?.[key] === value) ? trial.data : undefined
  return <Panel>
    <PanelHeader title={`试跑订单 · v${definition.version}`} />
    <form className="form-grid" onSubmit={event => { event.preventDefault(); if (facts.amountMinor && facts.skuId && facts.shopId && at && !trial.isPending) trial.mutate(facts) }}>
      <label className="field"><span>商品 SKU ID</span><input aria-label="试跑商品 SKU ID" value={sku} onChange={event => setSku(event.target.value)} maxLength={128} required /></label>
      <label className="field"><span>门店 ID</span><input aria-label="试跑门店 ID" value={shop} onChange={event => setShop(event.target.value)} maxLength={128} required /></label>
      <label className="field"><span>单商品订单金额（元）</span><input aria-label="单商品订单金额（元）" value={amount} onChange={event => setAmount(event.target.value)} inputMode="decimal" required /></label>
      <div className="field"><span>订单时间</span><DateTimePicker label="订单时间" value={at} onChange={setAt} /></div>
      <p className="field-hint span-2">按输入的单商品订单计算。库存、预算预占及其他活动冲突需在发布及履约链路核验。</p>
      <div className="span-2 workbench-actions"><Button type="submit" disabled={!facts.amountMinor || !facts.skuId || !facts.shopId || !at || trial.isPending}>{trial.isPending ? '正在试跑…' : '运行商品试跑'}</Button></div>
    </form>
    {trial.isError && <StateBanner tone="error" title="商品试跑失败" detail={problemDetail(trial.error)} />}
    {current && <div className="workbench-trial-result" role="status">
      <Badge tone={current.discountMinor > 0 ? 'good' : 'neutral'}>{outcomes[current.trace.at(-1)?.outcome ?? ''] ?? '已返回试跑结果'}</Badge>
      <dl className="workbench-facts"><div><dt>优惠前</dt><dd>{yuan.format(current.subtotalMinor / 100)}</dd></div><div><dt>优惠</dt><dd>{yuan.format(current.discountMinor / 100)}</dd></div><div><dt>试跑应付</dt><dd>{yuan.format(current.payableMinor / 100)}</dd></div></dl>
    </div>}
  </Panel>
}
