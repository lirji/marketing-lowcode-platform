import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArchiveRestore, CircleDollarSign, Save, ShieldCheck } from 'lucide-react'
import { Badge, Button, DemoBanner, EmptyState, PageHeader, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { ApiProblem, api } from '../../shared/api/client'
import { catalogProblemDetail, problemDetail } from '../../shared/api/problem'
import { useAuth } from '../../shared/auth/useAuth'
import type { BenefitView } from '../../shared/api/schemas'
import { SkuPickerField, skuFaceValue, validityLabel } from './SkuPickerField'

type Form = {
  benefitId: string
  name: string
  status: string
  resourceKey: string
  benefitSkuId: string
  type: string
  validity: string
  thresholdMinor: number
  discountMinor: number
  scope: string
  platformShare: number
  cancelUnlock: boolean
  partialRefund: boolean
  fullRefundReturn: boolean
  reverseLedger: boolean
  reclaimGift: boolean
  expireReturn: boolean
}

const DEFAULT_FORM: Form = {
  benefitId: 'coupon-ha-80',
  name: '家电满 500 减 80 券',
  status: 'DRAFT',
  resourceKey: '',
  benefitSkuId: '',
  type: 'COUPON',
  validity: 'relative',
  thresholdMinor: 50000,
  discountMinor: 8000,
  scope: 'category:LARGE_APPLIANCE AND shop:SELF_OPERATED',
  platformShare: 60,
  cancelUnlock: true,
  partialRefund: true,
  fullRefundReturn: true,
  reverseLedger: true,
  reclaimGift: false,
  expireReturn: true,
}

function emptyForm(): Form {
  return {
    benefitId: '',
    name: '',
    status: 'DRAFT',
    resourceKey: '',
    benefitSkuId: '',
    type: 'COUPON',
    validity: 'relative',
    thresholdMinor: 0,
    discountMinor: 0,
    scope: '',
    platformShare: 60,
    cancelUnlock: true,
    partialRefund: true,
    fullRefundReturn: true,
    reverseLedger: true,
    reclaimGift: false,
    expireReturn: true,
  }
}

function asNumber(value: unknown, fallback: number) {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

function asBoolean(value: unknown, fallback: boolean) {
  if (typeof value === 'boolean') return value
  if (value === 'true' || value === 'false') return value === 'true'
  return fallback
}

function fromBenefit(item: BenefitView): Form {
  const policy = item.policy ?? {}
  return {
    benefitId: item.benefitId,
    name: item.name,
    status: item.status,
    resourceKey: item.resourceKey ?? '',
    benefitSkuId: item.benefitSkuId ?? '',
    type: String(policy.type ?? 'COUPON'),
    validity: String(policy.validity ?? 'relative'),
    thresholdMinor: asNumber(policy.thresholdMinor, 0),
    discountMinor: asNumber(policy.discountMinor, 0),
    scope: String(policy.scope ?? ''),
    platformShare: asNumber(policy.platformShare, 60),
    cancelUnlock: asBoolean(policy.cancelUnlock, true),
    partialRefund: asBoolean(policy.partialRefund, true),
    fullRefundReturn: asBoolean(policy.fullRefundReturn, true),
    reverseLedger: asBoolean(policy.reverseLedger, true),
    reclaimGift: asBoolean(policy.reclaimGift, false),
    expireReturn: asBoolean(policy.expireReturn, true),
  }
}

function policyFrom(form: Form) {
  return {
    type: form.type,
    validity: form.validity,
    thresholdMinor: form.thresholdMinor,
    discountMinor: form.discountMinor,
    scope: form.scope,
    platformShare: form.platformShare,
    cancelUnlock: form.cancelUnlock,
    partialRefund: form.partialRefund,
    fullRefundReturn: form.fullRefundReturn,
    reverseLedger: form.reverseLedger,
    reclaimGift: form.reclaimGift,
    expireReturn: form.expireReturn,
  }
}

function benefitLabel(item: BenefitView) {
  return `${item.benefitId} · ${item.name} · ${item.status} v${item.version}`
}

function pickInitialBenefit(items: BenefitView[], preferredId?: string) {
  if (!preferredId) return undefined
  return items.find((item) => item.benefitId === preferredId)
}

function matchesBenefitQuery(item: BenefitView, query: string) {
  const needle = query.trim().toLowerCase()
  if (!needle) return true
  return item.benefitId.toLowerCase().includes(needle) || item.name.toLowerCase().includes(needle)
}

function saveErrorDetail(error: unknown) {
  if (error instanceof ApiProblem && error.code === 'SKU_NOT_ACTIVE') {
    return error.problem.detail || '所选 SKU 未处于已投放状态，发布被拒绝。'
  }
  return problemDetail(error)
}

export function BenefitEditorPage() {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const [searchParams] = useSearchParams()
  const [form, setForm] = useState<Form>(() => (api.demoMode ? DEFAULT_FORM : emptyForm()))
  const [notice, setNotice] = useState('')
  const [hydrated, setHydrated] = useState(false)
  const [baseline, setBaseline] = useState(() => JSON.stringify(api.demoMode ? DEFAULT_FORM : emptyForm()))
  const [benefitQuery, setBenefitQuery] = useState('')
  const canWrite = auth.hasPermission('benefit:write')
  const merchantShare = 100 - form.platformShare
  const platformAmount = ((form.discountMinor / 100) * form.platformShare / 100).toFixed(2)
  const merchantAmount = ((form.discountMinor / 100) * merchantShare / 100).toFixed(2)
  const benefits = useQuery({ queryKey: ['benefits'], queryFn: api.benefits, enabled: !api.demoMode && auth.hasPermission('benefit:read') })
  const accounts = useQuery({ queryKey: ['funding-accounts'], queryFn: api.fundingAccounts, enabled: !api.demoMode && auth.hasPermission('funding:account-read') })
  const skus = useQuery({ queryKey: ['benefit-skus', 'ACTIVE'], queryFn: () => api.benefitSkus('ACTIVE'), enabled: !api.demoMode && auth.hasPermission('benefit:read') })
  const account = (accounts.data ?? []).find((item) => item.resourceKey === form.resourceKey)
  const selectedSku = (skus.data ?? []).find((item) => item.skuId === form.benefitSkuId)
  const templateLocked = Boolean(selectedSku)
  const preferredBenefitId = searchParams.get('benefitId')?.trim() || undefined
  const visibleBenefits = useMemo(
    () => (benefits.data ?? []).filter((item) => matchesBenefitQuery(item, benefitQuery)),
    [benefitQuery, benefits.data],
  )

  useEffect(() => {
    if (hydrated || api.demoMode) {
      if (api.demoMode && !hydrated) setHydrated(true)
      return
    }
    if (benefits.isPending) return
    const initial = pickInitialBenefit(benefits.data ?? [], preferredBenefitId)
    if (initial) {
      const next = fromBenefit(initial)
      setForm(next)
      setBaseline(JSON.stringify(next))
    }
    setHydrated(true)
  }, [benefits.data, benefits.isPending, hydrated, preferredBenefitId])

  const save = useMutation({
    mutationFn: () => api.putBenefit(form.benefitId.trim(), {
      name: form.name.trim(),
      status: form.status,
      resourceKey: form.resourceKey,
      benefitSkuId: form.benefitSkuId.trim() || null,
      policy: policyFrom(form),
    }),
    onSuccess: (value) => {
      const next = fromBenefit(value)
      setNotice(`已写入 ${value.benefitId} v${value.version}`)
      setForm(next)
      setBaseline(JSON.stringify(next))
      void queryClient.invalidateQueries({ queryKey: ['benefits'] })
    },
  })
  const patch = (next: Partial<Form>) => setForm((current) => ({ ...current, ...next }))
  const dirty = JSON.stringify(form) !== baseline
  const typeMismatch = Boolean(selectedSku && form.type && selectedSku.benefitType && form.type !== selectedSku.benefitType)
  const saveBlocked = api.demoMode || !canWrite || save.isPending || !form.benefitId.trim() || !form.name.trim() || typeMismatch
  const saveHint = api.demoMode
    ? '演示模式不可执行'
    : !canWrite
      ? '当前身份没有 benefit:write'
      : !form.benefitId.trim() || !form.name.trim()
        ? '请先填写权益 ID 和名称'
        : typeMismatch
          ? `权益类型 ${form.type} 与模板 ${selectedSku?.benefitType} 不一致`
          : '写入 PUT /api/v1/benefits/{id}'
  const saveButton = (label: string) => (
    <Button tone="primary" disabled={saveBlocked} title={saveHint} onClick={() => save.mutate()}>
      <Save size={15} />{label}
    </Button>
  )

  return (
    <div className="workspace benefit-page">
      <PageHeader
        eyebrow={api.demoMode ? 'BENEFIT_POLICY · Coupon v9' : `BENEFIT_POLICY · ${form.status}`}
        title={form.name || '权益定义'}
        description="权益状态、资金来源、库存 bucket、退款与追回共同构成可对账的履约策略。"
        actions={(
          <>
            <Button disabled title="历史版本列表尚未接入"><ArchiveRestore size={15} />历史版本</Button>
            {saveButton('保存策略')}
          </>
        )}
      />
      <DemoBanner />
      {!api.demoMode && <StateBanner tone="info" title="策略写入 policy 映射" detail="出资比例、门槛与开关保存在权益 policy；资源守恒读取资金账户。有效期与面额以已绑定模板为准。" />}
      {notice && <StateBanner tone="success" title="已写入控制面" detail={notice} />}
      {benefits.isError && <StateBanner tone="error" title="权益加载失败" detail={problemDetail(benefits.error)} />}
      {!api.demoMode && !benefits.isPending && !benefits.isError && (benefits.data?.length ?? 0) === 0 && (
        <StateBanner
          tone="warn"
          title={`当前业务租户 ${auth.tenantId || '未声明'} 下没有权益`}
          detail="权益定义按货主隔离。请确认顶栏业务租户与建商品、建活动时相同。"
        />
      )}
      {accounts.isError && <StateBanner tone="error" title="资金账户加载失败" detail={problemDetail(accounts.error)} />}
      {save.isError && <StateBanner tone="error" title="保存失败" detail={saveErrorDetail(save.error)} />}
      {form.status === 'ACTIVE' && !form.benefitSkuId.trim() && !api.demoMode && (
        <StateBanner tone="warn" title="发布需要已投放 SKU" detail="草稿可不绑。状态设为 ACTIVE 时，服务端会校验 benefitSkuId。" />
      )}
      {!api.demoMode && (
        <div className="benefit-picker">
          <div className="form-grid">
            <label className="field"><span>权益 ID</span><input aria-label="权益 ID" value={form.benefitId} onChange={(event) => patch({ benefitId: event.target.value })} /></label>
            <label className="field">
              <span>筛选已有权益</span>
              <input
                aria-label="筛选已有权益"
                placeholder="输入 ID 或名称，例如 coupon-trace-ha-80"
                value={benefitQuery}
                onChange={(event) => setBenefitQuery(event.target.value)}
              />
            </label>
            <label className="field span-2">
              <span>已有权益{(benefits.data?.length ?? 0) > 0 ? ` · ${visibleBenefits.length}/${benefits.data?.length}` : ''}</span>
              <select
                aria-label="已有权益"
                value={(benefits.data ?? []).some((item) => item.benefitId === form.benefitId) ? form.benefitId : ''}
                onChange={(event) => {
                  const item = (benefits.data ?? []).find((row) => row.benefitId === event.target.value)
                  if (item) setForm(fromBenefit(item))
                }}
              >
                <option value="">新建 / 手动输入 ID</option>
                {visibleBenefits.map((item) => (
                  <option key={item.benefitId} value={item.benefitId}>{benefitLabel(item)}</option>
                ))}
              </select>
            </label>
          </div>
          {visibleBenefits.length > 0 && (
            <ul className="benefit-choice-list">
              {visibleBenefits.map((item) => (
                <li key={item.benefitId}>
                  <button
                    type="button"
                    aria-pressed={item.benefitId === form.benefitId}
                    onClick={() => setForm(fromBenefit(item))}
                  >
                    <strong>{item.benefitId}</strong>
                    <span>{item.name} · {item.status} v{item.version}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
          {benefitQuery.trim() && visibleBenefits.length === 0 && (
            <p className="benefit-picker-empty">没有匹配「{benefitQuery.trim()}」的权益。权益按当前业务租户隔离。</p>
          )}
        </div>
      )}
      <div className="benefit-layout">
        <div className="benefit-main">
          <Panel>
            <PanelHeader eyebrow="SKU TEMPLATE" title="绑定权益模板" aside={<>{dirty && <Badge tone="warn">有未保存更改</Badge>}{saveButton('保存')}</>} />
            {api.demoMode ? (
              <StateBanner tone="info" title="演示模式不绑定真实 SKU" detail="关闭 DEMO_MODE 后从 GET /api/v1/benefit-skus 读取已投放模板。" />
            ) : (
              <>
                <SkuPickerField
                  value={form.benefitSkuId}
                  onChange={(benefitSkuId) => patch({ benefitSkuId })}
                  disabled={!canWrite}
                  skus={skus.data ?? []}
                  loading={skus.isPending}
                  error={skus.isError ? catalogProblemDetail(skus.error, auth.tenantId) : undefined}
                />
                {typeMismatch && (
                  <StateBanner
                    tone="error"
                    title="权益类型与模板类型不一致"
                    detail={`当前权益是 ${form.type}，所选 SKU ${selectedSku?.skuId} 是 ${selectedSku?.benefitType}。保存已禁用，请改类型或换模板。`}
                  />
                )}
                <div className="sku-bind-actions">
                  <p>选完模板只改本地表单，必须点保存才会写入 benefitSkuId。{dirty ? ' 当前有未保存更改。' : ''}</p>
                  {saveButton('保存')}
                </div>
              </>
            )}
          </Panel>
          <Panel>
            <PanelHeader eyebrow="DEFINITION" title="权益定义" aside={<Badge tone={form.status === 'ACTIVE' ? 'good' : 'warn'}>{form.status}</Badge>} />
            <div className="form-grid">
              <label className="field"><span>名称</span><input value={form.name} onChange={(event) => patch({ name: event.target.value })} /></label>
              <label className="field">
                <span>状态</span>
                <select value={form.status} onChange={(event) => patch({ status: event.target.value })}>
                  <option>DRAFT</option>
                  <option>ACTIVE</option>
                  <option>RETIRED</option>
                </select>
              </label>
              <label className="field">
                <span>权益类型</span>
                <select value={form.type} onChange={(event) => patch({ type: event.target.value })}>
                  <option>COUPON</option>
                  <option>POINT</option>
                  <option>GIFT</option>
                  <option>PRIZE_CHANCE</option>
                </select>
              </label>
              <label className="field">
                <span>有效期</span>
                {templateLocked && selectedSku ? (
                  <input readOnly value={validityLabel(selectedSku)} title="以权益模板为准" />
                ) : (
                  <select value={form.validity} onChange={(event) => patch({ validity: event.target.value })}>
                    <option value="relative">领取后 7 天</option>
                    <option value="window">固定时间窗</option>
                  </select>
                )}
              </label>
              <label className="field"><span>门槛（分）</span><input type="number" value={form.thresholdMinor} onChange={(event) => patch({ thresholdMinor: Number(event.target.value) })} /></label>
              <label className="field">
                <span>优惠（分）</span>
                {templateLocked && selectedSku ? (
                  <input readOnly value={skuFaceValue(selectedSku.faceValueMinor, selectedSku.currency)} title="以权益模板面额为准" />
                ) : (
                  <input type="number" value={form.discountMinor} onChange={(event) => patch({ discountMinor: Number(event.target.value) })} />
                )}
              </label>
              <label className="field span-2"><span>适用商品范围</span><input value={form.scope} onChange={(event) => patch({ scope: event.target.value })} /></label>
            </div>
          </Panel>
          <Panel>
            <PanelHeader eyebrow="FUNDING" title="出资与成本中心" aside={<Badge tone="good">合计 100%</Badge>} />
            <div className="funding-slider">
              <label>
                <span>平台出资</span>
                <strong>{form.platformShare}% · ¥{platformAmount}</strong>
                <input type="range" min="0" max="100" value={form.platformShare} onChange={(event) => patch({ platformShare: Number(event.target.value) })} />
              </label>
              <label>
                <span>商家出资</span>
                <strong>{merchantShare}% · ¥{merchantAmount}</strong>
                <progress max="100" value={merchantShare} />
              </label>
            </div>
            {api.demoMode ? (
              <div className="funding-centers">
                <div><CircleDollarSign size={16} /><span>平台营销中心 / CC-PROMO-2026</span><Badge tone="good">预算 ¥12M</Badge></div>
                <div><CircleDollarSign size={16} /><span>家电事业部 / CC-HA-1101</span><Badge tone="good">预算 ¥8M</Badge></div>
              </div>
            ) : (
              <label className="field">
                <span>绑定资金账户</span>
                <select value={form.resourceKey} onChange={(event) => patch({ resourceKey: event.target.value })}>
                  <option value="">不绑定</option>
                  {(accounts.data ?? []).map((item) => (
                    <option key={item.resourceKey} value={item.resourceKey}>{item.resourceKey} · {item.currency ?? ''} · 可用 {item.available}</option>
                  ))}
                </select>
              </label>
            )}
          </Panel>
          <Panel>
            <PanelHeader eyebrow="LIFECYCLE" title="状态与补偿策略" />
            <div className="lifecycle"><span>AVAILABLE</span><i>领取</i><span>CLAIMED</span><i>锁定</i><span>LOCKED</span><i>核销</i><span>REDEEMED</span></div>
            <div className="policy-grid">
              <label><input type="checkbox" checked={form.cancelUnlock} onChange={(event) => patch({ cancelUnlock: event.target.checked })} />取消订单自动解锁</label>
              <label><input type="checkbox" checked={form.partialRefund} onChange={(event) => patch({ partialRefund: event.target.checked })} />部分退款按行分摊</label>
              <label><input type="checkbox" checked={form.fullRefundReturn} onChange={(event) => patch({ fullRefundReturn: event.target.checked })} />全额退款返还券</label>
              <label><input type="checkbox" checked={form.reverseLedger} onChange={(event) => patch({ reverseLedger: event.target.checked })} />冲正产生反向流水</label>
              <label><input type="checkbox" checked={form.reclaimGift} onChange={(event) => patch({ reclaimGift: event.target.checked })} />赠品退款后追回</label>
              <label><input type="checkbox" checked={form.expireReturn} onChange={(event) => patch({ expireReturn: event.target.checked })} />过期库存归还 bucket</label>
            </div>
          </Panel>
        </div>
        <aside>
          {api.demoMode ? (
            <>
              <Panel>
                <PanelHeader eyebrow="RESOURCE GUARD" title="资源守恒" />
                <div className="conservation">
                  <div><span>授权</span><strong>1,000,000</strong></div>
                  <b>=</b>
                  <div><span>可用</span><strong>731,420</strong></div>
                  <b>+</b>
                  <div><span>预占</span><strong>84,220</strong></div>
                  <b>+</b>
                  <div><span>已耗</span><strong>184,360</strong></div>
                </div>
                <StateBanner tone="success" title="守恒式成立" detail="最近一次 reconciliation：10:36:12，差异 0。" />
              </Panel>
              <Panel>
                <PanelHeader eyebrow="REGIONAL ESCROW" title="区域库存" />
                <div className="bucket-list">{[['华东', '420K', '42%'], ['华北', '260K', '26%'], ['华南', '240K', '24%'], ['机动池', '80K', '8%']].map(([name, value, width]) => <div key={name}><span>{name}</span><i><b style={{ width }} /></i><strong>{value}</strong></div>)}</div>
                <div className="governance-note"><ShieldCheck /><div><strong>Fencing epoch 42</strong><p>资源 home region：cn-east；跨区切换前必须隔离旧 epoch。</p></div></div>
              </Panel>
            </>
          ) : (
            <>
              <Panel>
                <PanelHeader eyebrow="RESOURCE GUARD" title="资源守恒" />
                {account ? (
                  <>
                    <div className="conservation">
                      <div><span>授权</span><strong>{account.authorized}</strong></div>
                      <b>=</b>
                      <div><span>可用</span><strong>{account.available}</strong></div>
                      <b>+</b>
                      <div><span>预占</span><strong>{account.reserved}</strong></div>
                      <b>+</b>
                      <div><span>已耗</span><strong>{account.consumed}</strong></div>
                    </div>
                    <StateBanner
                      tone={account.authorized === account.available + account.reserved + account.consumed + account.returned ? 'success' : 'warn'}
                      title={account.authorized === account.available + account.reserved + account.consumed + account.returned ? '守恒式成立' : '账户分项与授权不一致'}
                      detail={`returned ${account.returned} · fencing ${account.fencingEpoch ?? '—'}`}
                    />
                  </>
                ) : (
                  <EmptyState title="未绑定资金账户" detail="选择 resourceKey 后显示授权 / 可用 / 预占 / 已耗。" />
                )}
              </Panel>
              <Panel>
                <PanelHeader eyebrow="REGIONAL ESCROW" title="区域库存" />
                <EmptyState title="没有区域分桶接口" detail="账户只返回合计fencing，不按华东/华北拆分。" />
              </Panel>
            </>
          )}
        </aside>
      </div>
    </div>
  )
}
