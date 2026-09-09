import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { useLocation, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { CheckCircle2, FlaskConical, Plus, Save, Trash2 } from 'lucide-react'
import { Badge, Button, DemoBanner, PageHeader, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { api, optionalResource } from '../../shared/api/client'
import { problemDetail, catalogProblemDetail } from '../../shared/api/problem'
import { isReferralSimulation, REFERRAL_POLICY_DIALECT, type BenefitSku, type BenefitView, type ReferralSimulationResult } from '../../shared/api/schemas'
import { campaignNameFromLocationState, designerHeading } from '../designers/designerBinding'
import { SkuPickerField } from '../designers/SkuPickerField'
import { orgChoicesFromIdentity, shopChoicesFromIdentity } from '../../shared/auth/scopeChoices'
import { useAuth } from '../../shared/auth/useAuth'
import {
  emptyReferralDraft,
  emptyReward,
  fromReferralGraph,
  pinCatalogVersion,
  splitCatalogVersion,
  toReferralGraph,
  validateReferralDraft,
  type FieldError,
  type ReferralDraft,
  type ReferralRewardDraft,
} from './referralGraph'
import { documentedSimulationExample, emptySimulationFacts, validateSimulationFacts, type ReferralSimulationFacts } from './referralSimulate'
import { qualificationReasonLabel, qualificationStateLabel } from './referralStatus'

function termsPreview(draft: ReferralDraft, campaignName: string): string {
  const goal = draft.goalType === 'FIRST_ORDER_SETTLED' ? '权威首单结算' : draft.goalType === 'REGISTERED_NEW_CUSTOMER' ? '注册新客' : '资格模式未选'
  const rewards = draft.rewards.length === 0
    ? '尚未配置奖励'
    : draft.rewards.map((item) => `${item.role === 'INVITEE' ? '被邀请人' : '邀请人'} ${item.mode === 'MILESTONE' ? `达到 ${item.threshold || '—'} 人` : '逐人'}：权益 ${item.benefitDefinitionVersion || '未引用'} / SKU ${item.skuVersion || '未引用'}`).join('；')
  return [
    `${campaignName || '未命名活动'}（邀请有礼）`,
    `活动时间 ${draft.startsAt || '未填'} 至 ${draft.endsAt || '未填'}，结算截止 ${draft.settlementEndsAt || '未填'}。`,
    `归因仅支持首次有效绑定；被邀请人范围仅支持权威新客。绑定期限 ${draft.maxBindAgeSeconds || '未填'} 秒，起算点以后端合同为准，前端不推断。`,
    `资格：${goal}。达标窗口 ${draft.qualificationWindowSeconds || '未填'} 秒，观察期 ${draft.observationSeconds || '未填'} 秒，晚到宽限 ${draft.lateArrivalGraceSeconds || '未填'} 秒。`,
    `金额最小单位 ${draft.minNetAmountMinor || '未填'} ${draft.currency || ''}。退款后净额不足则资格失效，同档位再次达标不重发。`,
    `奖励：${rewards}。HELD / 已受理 / 仿真候选均不是到账。`,
    '裂变发布链当前未接通，校验通过不等于可发布。',
  ].join('\n')
}

function errorMap(errors: FieldError[]): Record<string, string> {
  return Object.fromEntries(errors.map((item) => [item.field, item.message]))
}

function sectionErrors(errors: FieldError[], section: string): FieldError[] {
  return errors.filter((item) => item.section === section)
}

export function ReferralDesignerPage() {
  const [params] = useSearchParams()
  const location = useLocation()
  const auth = useAuth()
  const campaignId = params.get('campaignId') ?? undefined
  const campaigns = useQuery({
    queryKey: ['campaigns'],
    queryFn: api.campaigns,
    enabled: Boolean(campaignId) && !api.demoMode,
  })
  const campaign = campaigns.data?.find((item) => item.id === campaignId)
  const campaignName = campaign?.name ?? campaignNameFromLocationState(location.state)
  const heading = designerHeading({
    campaignId,
    campaignName,
    boundSuffix: '邀请有礼',
    demoTitle: '邀请有礼草稿',
    demoVersion: '尚未保存',
  })
  const orgOptions = orgChoicesFromIdentity(auth.organizations)
  const shopOptions = shopChoicesFromIdentity(auth.shops)
  const [draft, setDraft] = useState<ReferralDraft>(() => emptyReferralDraft({
    organizationId: campaign?.organizationId ?? orgOptions[0] ?? '',
    shopId: campaign?.shopId ?? shopOptions[0]?.value ?? '',
  }))
  const [facts, setFacts] = useState<ReferralSimulationFacts>(() => emptySimulationFacts())
  const [fieldErrors, setFieldErrors] = useState<FieldError[]>([])
  const [factErrors, setFactErrors] = useState<FieldError[]>([])
  const [loadErrors, setLoadErrors] = useState<FieldError[]>([])
  const [savedVersion, setSavedVersion] = useState<number>()
  const [definitionId, setDefinitionId] = useState(campaignId ? `referral-${campaignId}` : 'referral-draft')
  const [status, setStatus] = useState<'saved' | 'dirty' | 'validated' | 'simulated'>('saved')
  const [notice, setNotice] = useState<string>()
  const [loadError, setLoadError] = useState<string>()
  const [serverIssues, setServerIssues] = useState<{ code: string; message: string; pointer: string; severity: string }[]>([])
  const [simulation, setSimulation] = useState<ReferralSimulationResult>()
  const canWrite = auth.hasPermission('definition:write')
  const live = !api.demoMode && Boolean(campaignId) && canWrite

  const benefits = useQuery({
    queryKey: ['benefits'],
    queryFn: api.benefits,
    enabled: !api.demoMode && auth.hasPermission('benefit:read'),
  })
  const skus = useQuery({
    queryKey: ['benefit-skus', 'ACTIVE'],
    queryFn: () => api.benefitSkus('ACTIVE'),
    enabled: !api.demoMode && auth.hasPermission('benefit:read'),
  })

  useEffect(() => {
    let active = true
    const load = async () => {
      if (api.demoMode || !campaignId) return
      try {
        const bundle = await optionalResource(() => api.latestDefinition(campaignId, REFERRAL_POLICY_DIALECT))
        if (!active || !bundle?.graph) return
        const parsed = fromReferralGraph(bundle.graph)
        setDraft(parsed.draft)
        setLoadErrors(parsed.errors)
        setSavedVersion(bundle.version)
        setDefinitionId(bundle.definitionId)
        setStatus('saved')
        setLoadError(undefined)
      } catch (cause) {
        if (active) setLoadError(problemDetail(cause, '未能从控制面加载裂变定义'))
      }
    }
    void load()
    return () => { active = false }
  }, [campaignId])

  const patch = (next: Partial<ReferralDraft>) => {
    setDraft((current) => ({ ...current, ...next }))
    setStatus('dirty')
    setNotice(undefined)
    setSimulation(undefined)
  }
  const patchReward = (index: number, next: Partial<ReferralRewardDraft>) => {
    setDraft((current) => ({
      ...current,
      rewards: current.rewards.map((item, itemIndex) => itemIndex === index ? { ...item, ...next } : item),
    }))
    setStatus('dirty')
    setSimulation(undefined)
  }

  const persist = async () => {
    const errors = validateReferralDraft(draft)
    setFieldErrors(errors)
    if (errors.length > 0) throw new Error('请先修正分区表单错误')
    if (!live || !campaignId) throw new Error('演示模式或未绑定活动，不会写入控制面')
    const graph = toReferralGraph({ definitionId, draft, title: heading.title })
    return api.saveDefinition({ campaignId, graph })
  }

  const saveMutation = useMutation({
    mutationFn: persist,
    onSuccess: (value) => {
      setSavedVersion(value.version)
      setDefinitionId(value.definitionId)
      setStatus('saved')
      setNotice(`已写入版本 v${value.version}，尚未发布`)
    },
  })
  const validateMutation = useMutation({
    mutationFn: async () => {
      const saved = await persist()
      setSavedVersion(saved.version)
      setDefinitionId(saved.definitionId)
      return api.validate(saved.definitionId, saved.version)
    },
    onSuccess: (value) => {
      setServerIssues(value.issues)
      setStatus(value.valid ? 'validated' : 'dirty')
      const catalogWarn = value.issues.some((item) => item.code === 'REFERRAL_CATALOG_UNVERIFIED')
      if (value.valid && catalogWarn) {
        setNotice('规则语义合法，但目录未核验。VALIDATED 不等于可发布。')
        return
      }
      setNotice(value.valid ? '校验通过，仅表示规则合法，不等于可发布。' : '校验发现错误，未进入可提交状态')
    },
  })
  const simulateMutation = useMutation({
    mutationFn: async () => {
      const local = validateSimulationFacts(facts)
      setFactErrors(local)
      if (local.length > 0) throw new Error('请先修正仿真输入')
      if (savedVersion == null) throw new Error('请先保存并校验后再仿真')
      if (!live) throw new Error('演示模式不会调用仿真服务')
      return api.simulate(definitionId, savedVersion, facts)
    },
    onSuccess: (value) => {
      if (!isReferralSimulation(value)) {
        throw new Error('控制面返回了旧价格仿真，不能当作裂变结果')
      }
      setSimulation(value)
      setStatus('simulated')
      if (!value.simulationOnly || value.evidenceAuthority !== 'SIMULATED_INPUT') {
        setNotice('仿真返回形态异常，不能当作无副作用成功。')
        return
      }
      setNotice('仿真完成：结果仅为候选，不会实际发奖，也不是到账。')
    },
  })

  const errors = fieldErrors
  const actionError = saveMutation.error ?? validateMutation.error ?? simulateMutation.error
  const catalogError = benefits.isError ? catalogProblemDetail(benefits.error, auth.tenantId) : skus.isError ? catalogProblemDetail(skus.error, auth.tenantId) : undefined

  return (
    <div className="workspace referral-page">
      <PageHeader
        eyebrow={`${REFERRAL_POLICY_DIALECT}${campaignId ? ` · ${campaignId}` : ''}`}
        title={heading.title}
        description="按分区填写邀请有礼规则，保存时序列化为固定线性图。不开放自由拖拽节点。"
        actions={(
          <>
            <Button onClick={() => saveMutation.mutate()} disabled={!live || saveMutation.isPending} title={live ? '写入控制面定义' : '演示模式或未绑定活动不可保存'}>
              <Save size={15} />保存
            </Button>
            <Button onClick={() => validateMutation.mutate()} disabled={!live || validateMutation.isPending} title={live ? '调用控制面校验' : '演示模式不可执行'}>
              <CheckCircle2 size={15} />校验
            </Button>
            <Button onClick={() => simulateMutation.mutate()} disabled={!live || simulateMutation.isPending} title={live ? '无副作用仿真' : '演示模式不可执行'}>
              <FlaskConical size={15} />仿真
            </Button>
            <Button disabled title="REFERRAL_RELEASE_NOT_AVAILABLE：裂变发布链未接通">提交审核</Button>
          </>
        )}
      />
      <DemoBanner />
      <p className="referral-save-state" aria-live="polite">
        {savedVersion ? `v${savedVersion}` : heading.version}
        {' · '}
        {status === 'saved' ? '已保存' : status === 'dirty' ? '有未保存更改' : status === 'validated' ? '校验通过（不可发布）' : '仿真完成（候选）'}
      </p>
      {!campaignId && !api.demoMode && <StateBanner tone="info" title="未绑定活动" detail="请从「计划与活动」创建或打开活动后再设计。保存不会写入控制面。" />}
      <StateBanner tone="warn" title="裂变发布链未接通" detail="submit / ACK / 激活会返回 REFERRAL_RELEASE_NOT_AVAILABLE。校验通过或仿真候选都不能当作可发布或已到账。" />
      {(actionError || loadError) && <StateBanner tone="error" title="设计器操作失败" detail={loadError ?? problemDetail(actionError)} />}
      {notice && <StateBanner tone={notice.includes('异常') || notice.includes('错误') ? 'warn' : 'success'} title="操作结果" detail={notice} />}
      {loadErrors.length > 0 && <StateBanner tone="warn" title="已保存图与当前合同不完全一致" detail={loadErrors.map((item) => item.message).join('；')} />}
      {catalogError && <StateBanner tone="error" title="权益或 SKU 目录不可用" detail={catalogError} />}

      <div className="referral-layout">
        <div className="referral-main">
          <Section title="基本信息 / 作用域" errors={sectionErrors(errors, '基本信息')}>
            <label className="field">
              <span>开始时间（UTC Instant）</span>
              <input value={draft.startsAt} onChange={(event) => patch({ startsAt: event.target.value })} aria-invalid={Boolean(errorMap(errors).startsAt)} placeholder="2026-09-01T00:00:00Z" />
              <small id="startsAt-hint">{errorMap(errors).startsAt ?? '必须带 Z，不预填活动天数。'}</small>
            </label>
            <label className="field">
              <span>结束时间</span>
              <input value={draft.endsAt} onChange={(event) => patch({ endsAt: event.target.value })} aria-invalid={Boolean(errorMap(errors).endsAt)} placeholder="2026-09-10T00:00:00Z" />
              {errorMap(errors).endsAt && <small>{errorMap(errors).endsAt}</small>}
            </label>
            <label className="field">
              <span>结算截止</span>
              <input value={draft.settlementEndsAt} onChange={(event) => patch({ settlementEndsAt: event.target.value })} aria-invalid={Boolean(errorMap(errors).settlementEndsAt)} placeholder="2026-09-20T00:00:00Z" />
              {errorMap(errors).settlementEndsAt && <small>{errorMap(errors).settlementEndsAt}</small>}
            </label>
            <label className="field">
              <span>组织</span>
              <select value={draft.organizationId} onChange={(event) => patch({ organizationId: event.target.value })}>
                {orgOptions.length === 0 && <option value="">当前身份没有组织</option>}
                {orgOptions.map((item) => <option key={item} value={item}>{item}</option>)}
                {draft.organizationId && !orgOptions.includes(draft.organizationId) && <option value={draft.organizationId}>{draft.organizationId}</option>}
              </select>
            </label>
            <label className="field span-2">
              <span>店铺</span>
              <select value={draft.shopId} onChange={(event) => patch({ shopId: event.target.value })} aria-invalid={Boolean(errorMap(errors).shopId)}>
                {shopOptions.map((item) => <option key={item.value || 'all-scope'} value={item.value}>{item.label}</option>)}
                {draft.shopId && !shopOptions.some((item) => item.value === draft.shopId) && <option value={draft.shopId}>{draft.shopId}</option>}
              </select>
              <small>{errorMap(errors).shopId ?? '编译器要求 shopId 非空。身份只有「全部店铺」时需改填具体店铺，不能提交空串。'}</small>
            </label>
          </Section>

          <Section title="参与及绑定" errors={sectionErrors(errors, '参与及绑定')}>
            <label className="field">
              <span>归因</span>
              <input readOnly value="FIRST_VALID_BIND" />
              <small>首期唯一合法值，前端不提供最后点击等回退。</small>
            </label>
            <label className="field">
              <span>被邀请人范围</span>
              <input readOnly value="NEW_CUSTOMER" />
              <small>首期只编译权威新客。</small>
            </label>
            <label className="field span-2">
              <span>maxBindAgeSeconds</span>
              <input value={draft.maxBindAgeSeconds} onChange={(event) => patch({ maxBindAgeSeconds: event.target.value })} aria-invalid={Boolean(errorMap(errors).maxBindAgeSeconds)} inputMode="numeric" placeholder="正整数秒" />
              <small>{errorMap(errors).maxBindAgeSeconds ?? '绑定期限起点尚未由业务确认，这里只提交秒数，不推断起算点。'}</small>
            </label>
          </Section>

          <Section title="新客或首单" errors={sectionErrors(errors, '新客或首单')}>
            <label className="field span-2">
              <span>资格模式</span>
              <select value={draft.goalType} onChange={(event) => patch({ goalType: event.target.value as ReferralDraft['goalType'] })} aria-invalid={Boolean(errorMap(errors).goalType)}>
                <option value="">请选择</option>
                <option value="REGISTERED_NEW_CUSTOMER">注册新客</option>
                <option value="FIRST_ORDER_SETTLED">权威首单结算</option>
              </select>
              {errorMap(errors).goalType && <small>{errorMap(errors).goalType}</small>}
            </label>
          </Section>

          <Section title="达标期限 / 金额 / 观察期" errors={sectionErrors(errors, '达标期限')}>
            <IntegerField label="达标窗口（秒）" value={draft.qualificationWindowSeconds} error={errorMap(errors).qualificationWindowSeconds} onChange={(value) => patch({ qualificationWindowSeconds: value })} />
            <IntegerField label="观察期（秒）" value={draft.observationSeconds} error={errorMap(errors).observationSeconds} onChange={(value) => patch({ observationSeconds: value })} />
            <IntegerField label="晚到宽限（秒）" value={draft.lateArrivalGraceSeconds} error={errorMap(errors).lateArrivalGraceSeconds} onChange={(value) => patch({ lateArrivalGraceSeconds: value })} />
            <IntegerField label="最低净额（最小货币单位）" value={draft.minNetAmountMinor} error={errorMap(errors).minNetAmountMinor} onChange={(value) => patch({ minNetAmountMinor: value })} />
            <label className="field">
              <span>币种</span>
              <input value={draft.currency} onChange={(event) => patch({ currency: event.target.value.toUpperCase() })} aria-invalid={Boolean(errorMap(errors).currency)} placeholder="CNY" maxLength={3} />
              <small>{errorMap(errors).currency ?? '3 位大写字母。不预填为已批准政策。'}</small>
            </label>
          </Section>

          <RewardSection
            draft={draft}
            errors={errors}
            benefits={benefits.data ?? []}
            skus={skus.data ?? []}
            loading={benefits.isPending || skus.isPending}
            catalogError={catalogError}
            onAdd={(reward) => { patch({ rewards: [...draft.rewards, reward] }); setStatus('dirty') }}
            onRemove={(index) => { patch({ rewards: draft.rewards.filter((_, itemIndex) => itemIndex !== index) }) }}
            onPatch={patchReward}
          />

          <Section title="退款规则" errors={[]}>
            <p className="referral-copy">退款后净额 = 已结算 − 累计退款。低于最低净额则资格失效。观察期内退款同样计入。同档位再次达标不重发第二份奖励。本分区没有额外节点字段，避免自造退款配置。</p>
          </Section>

          <Section title="条款预览" errors={[]}>
            <pre className="referral-terms">{termsPreview(draft, campaignName ?? '')}</pre>
          </Section>
        </div>

        <aside className="referral-aside">
          <Panel>
            <PanelHeader title="无副作用仿真" eyebrow="SIMULATED_INPUT" />
            <p className="referral-copy">以下输入全部按字符串提交。仿真不会写参与人、不会发奖。validCount 必须是本次假设后的人数，前端不会累加。</p>
            <div className="form-grid">
              <FactField label="boundAt" value={facts.boundAt} error={errorOf(factErrors, 'boundAt')} onChange={(value) => setFacts((current) => ({ ...current, boundAt: value }))} />
              <FactField label="now" value={facts.now} error={errorOf(factErrors, 'now')} onChange={(value) => setFacts((current) => ({ ...current, now: value }))} />
              <FactField label="relationId" value={facts.relationId} error={errorOf(factErrors, 'relationId')} onChange={(value) => setFacts((current) => ({ ...current, relationId: value }))} />
              <FactField label="validCount" value={facts.validCount} error={errorOf(factErrors, 'validCount')} onChange={(value) => setFacts((current) => ({ ...current, validCount: value }))} />
              <label className="field">
                <span>verified</span>
                <select value={facts.verified} onChange={(event) => setFacts((current) => ({ ...current, verified: event.target.value }))}>
                  <option value="">请选择</option>
                  <option value="true">true</option>
                  <option value="false">false</option>
                </select>
              </label>
              <label className="field">
                <span>newCustomerAtBind</span>
                <select value={facts.newCustomerAtBind} onChange={(event) => setFacts((current) => ({ ...current, newCustomerAtBind: event.target.value }))}>
                  <option value="">请选择</option>
                  <option value="YES">YES</option>
                  <option value="NO">NO</option>
                  <option value="UNKNOWN">UNKNOWN</option>
                </select>
              </label>
              <label className="field">
                <span>firstValidOrder</span>
                <select value={facts.firstValidOrder} onChange={(event) => setFacts((current) => ({ ...current, firstValidOrder: event.target.value }))}>
                  <option value="">请选择</option>
                  <option value="YES">YES</option>
                  <option value="NO">NO</option>
                  <option value="UNKNOWN">UNKNOWN</option>
                </select>
              </label>
              <FactField label="qualifyingFactAt（可空）" value={facts.qualifyingFactAt} error={errorOf(factErrors, 'qualifyingFactAt')} onChange={(value) => setFacts((current) => ({ ...current, qualifyingFactAt: value }))} />
              <FactField label="qualifyingFactReceivedAt（可空）" value={facts.qualifyingFactReceivedAt} error={errorOf(factErrors, 'qualifyingFactReceivedAt')} onChange={(value) => setFacts((current) => ({ ...current, qualifyingFactReceivedAt: value }))} />
              <FactField label="settledAmountMinor" value={facts.settledAmountMinor} error={errorOf(factErrors, 'settledAmountMinor')} onChange={(value) => setFacts((current) => ({ ...current, settledAmountMinor: value }))} />
              <FactField label="cumulativeRefundMinor" value={facts.cumulativeRefundMinor} error={errorOf(factErrors, 'cumulativeRefundMinor')} onChange={(value) => setFacts((current) => ({ ...current, cumulativeRefundMinor: value }))} />
              <FactField label="currency" value={facts.currency} error={errorOf(factErrors, 'currency')} onChange={(value) => setFacts((current) => ({ ...current, currency: value }))} />
            </div>
            <div className="referral-aside-actions">
              <Button onClick={() => setFacts(documentedSimulationExample())}>填入控制面测试示例</Button>
              <small>示例来自预览合同，不是生产默认政策。</small>
            </div>
            {simulation && <SimulationResultCard result={simulation} />}
          </Panel>
          <Panel>
            <PanelHeader title="校验问题" eyebrow={live ? '控制面' : '本地'} />
            {serverIssues.length === 0 ? <p className="muted">还没有控制面校验结果。</p> : (
              <ul className="referral-issues">
                {serverIssues.map((issue) => (
                  <li key={`${issue.code}-${issue.pointer}`}>
                    <Badge tone={issue.severity === 'ERROR' ? 'danger' : 'warn'}>{issue.severity}</Badge>
                    <div>
                      <strong>{issue.message}</strong>
                      <small>{issue.code} · {issue.pointer || '未定位'}</small>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </Panel>
        </aside>
      </div>
    </div>
  )
}

function errorOf(errors: FieldError[], field: string): string | undefined {
  return errors.find((item) => item.field === field)?.message
}

function Section({ title, errors, children }: { title: string; errors: FieldError[]; children: ReactNode }) {
  return (
    <Panel>
      <PanelHeader title={title} aside={errors.length > 0 ? <Badge tone="danger">{errors.length} 项</Badge> : undefined} />
      <div className="form-grid">{children}</div>
    </Panel>
  )
}

function IntegerField({ label, value, error, onChange }: { label: string; value: string; error?: string; onChange: (value: string) => void }) {
  return (
    <label className="field">
      <span>{label}</span>
      <input value={value} onChange={(event) => onChange(event.target.value)} aria-invalid={Boolean(error)} inputMode="numeric" />
      <small>{error ?? '空串、小数、超出安全整数不会被静默改成合法值。'}</small>
    </label>
  )
}

function FactField({ label, value, error, onChange }: { label: string; value: string; error?: string; onChange: (value: string) => void }) {
  return (
    <label className="field">
      <span>{label}</span>
      <input value={value} onChange={(event) => onChange(event.target.value)} aria-invalid={Boolean(error)} />
      {error && <small>{error}</small>}
    </label>
  )
}

function RewardSection({
  draft, errors, benefits, skus, loading, catalogError, onAdd, onRemove, onPatch,
}: {
  draft: ReferralDraft
  errors: FieldError[]
  benefits: BenefitView[]
  skus: BenefitSku[]
  loading: boolean
  catalogError?: string
  onAdd: (reward: ReferralRewardDraft) => void
  onRemove: (index: number) => void
  onPatch: (index: number, next: Partial<ReferralRewardDraft>) => void
}) {
  const inviter = useMemo(() => draft.rewards.map((item, index) => ({ item, index })).filter(({ item }) => item.role === 'INVITER' && item.mode === 'PER_RELATION'), [draft.rewards])
  const invitee = useMemo(() => draft.rewards.map((item, index) => ({ item, index })).filter(({ item }) => item.role === 'INVITEE'), [draft.rewards])
  const milestones = useMemo(() => draft.rewards.map((item, index) => ({ item, index })).filter(({ item }) => item.mode === 'MILESTONE'), [draft.rewards])
  return (
    <>
      <Panel>
        <PanelHeader title="邀请人逐人奖" aside={<Button onClick={() => onAdd(emptyReward({ role: 'INVITER', mode: 'PER_RELATION', threshold: '1' }))}><Plus size={14} />添加</Button>} />
        {inviter.length === 0 && <p className="muted">未配置。不要预填示例券。</p>}
        {inviter.map(({ item, index }) => (
          <RewardCard key={item.key} index={index} reward={item} errors={errors} benefits={benefits} skus={skus} loading={loading} catalogError={catalogError} onPatch={onPatch} onRemove={onRemove} />
        ))}
      </Panel>
      <Panel>
        <PanelHeader title="被邀请人奖" aside={<Button onClick={() => onAdd(emptyReward({ role: 'INVITEE', mode: 'PER_RELATION', threshold: '1' }))}><Plus size={14} />添加</Button>} />
        {invitee.length === 0 && <p className="muted">未配置。双方奖励独立，不要求另一方已履约。</p>}
        {invitee.map(({ item, index }) => (
          <RewardCard key={item.key} index={index} reward={item} errors={errors} benefits={benefits} skus={skus} loading={loading} catalogError={catalogError} onPatch={onPatch} onRemove={onRemove} />
        ))}
      </Panel>
      <Panel>
        <PanelHeader title="阶梯奖励" aside={<Button onClick={() => onAdd(emptyReward({ role: 'INVITER', mode: 'MILESTONE', threshold: '' }))}><Plus size={14} />添加档位</Button>} />
        {milestones.length === 0 && <p className="muted">不预填 3/5 人档。阶梯仅属于邀请人。</p>}
        {milestones.map(({ item, index }) => (
          <RewardCard key={item.key} index={index} reward={item} errors={errors} benefits={benefits} skus={skus} loading={loading} catalogError={catalogError} onPatch={onPatch} onRemove={onRemove} />
        ))}
      </Panel>
    </>
  )
}

function RewardCard({
  index, reward, errors, benefits, skus, loading, catalogError, onPatch, onRemove,
}: {
  index: number
  reward: ReferralRewardDraft
  errors: FieldError[]
  benefits: BenefitView[]
  skus: BenefitSku[]
  loading: boolean
  catalogError?: string
  onPatch: (index: number, next: Partial<ReferralRewardDraft>) => void
  onRemove: (index: number) => void
}) {
  const prefix = `rewards.${index}`
  const benefitPin = splitCatalogVersion(reward.benefitDefinitionVersion)
  const skuPin = splitCatalogVersion(reward.skuVersion)
  const selectedBenefit = benefits.find((item) => benefitPin && item.benefitId === benefitPin.id)
  const selectedSkuId = skuPin?.id ?? ''
  const orphanBenefit = Boolean(reward.benefitDefinitionVersion) && !selectedBenefit
  return (
    <div className="referral-reward-card">
      <div className="form-grid">
        <label className="field">
          <span>ruleId</span>
          <input value={reward.ruleId} onChange={(event) => onPatch(index, { ruleId: event.target.value })} aria-invalid={Boolean(errorOf(errors, `${prefix}.ruleId`))} />
          {errorOf(errors, `${prefix}.ruleId`) && <small>{errorOf(errors, `${prefix}.ruleId`)}</small>}
        </label>
        <label className="field">
          <span>门槛</span>
          <input value={reward.threshold} readOnly={reward.mode === 'PER_RELATION'} onChange={(event) => onPatch(index, { threshold: event.target.value })} aria-invalid={Boolean(errorOf(errors, `${prefix}.threshold`))} />
          <small>{errorOf(errors, `${prefix}.threshold`) ?? (reward.mode === 'PER_RELATION' ? '逐人门槛固定 1，数量固定 1。' : '人数档位，不要预填 3/5。')}</small>
        </label>
        <IntegerField label="个人封顶" value={reward.perSubjectLimit} error={errorOf(errors, `${prefix}.perSubjectLimit`)} onChange={(value) => onPatch(index, { perSubjectLimit: value })} />
        <IntegerField label="活动封顶" value={reward.campaignLimit} error={errorOf(errors, `${prefix}.campaignLimit`)} onChange={(value) => onPatch(index, { campaignLimit: value })} />
        <label className="field span-2">
          <span>权益定义版本</span>
          <select
            value={selectedBenefit ? pinCatalogVersion(selectedBenefit.benefitId, selectedBenefit.version) : reward.benefitDefinitionVersion}
            onChange={(event) => onPatch(index, { benefitDefinitionVersion: event.target.value })}
            disabled={loading || Boolean(catalogError)}
            aria-invalid={Boolean(errorOf(errors, `${prefix}.benefitDefinitionVersion`))}
          >
            <option value="">不自动选择，请指定</option>
            {orphanBenefit && <option value={reward.benefitDefinitionVersion}>{reward.benefitDefinitionVersion}（已冻结，当前目录没有）</option>}
            {benefits.map((item) => (
              <option key={`${item.benefitId}-${item.version}`} value={pinCatalogVersion(item.benefitId, item.version)}>
                {item.benefitId} · {item.name} · v{item.version} · {item.status}
              </option>
            ))}
          </select>
          <small>{errorOf(errors, `${prefix}.benefitDefinitionVersion`) ?? (catalogError ? catalogError : '引用实际权益版本，目录失败时不顶替。')}</small>
        </label>
        <div className="span-2">
          <SkuPickerField
            value={selectedSkuId}
            onChange={(skuId) => {
              const sku = skus.find((item) => item.skuId === skuId)
              onPatch(index, { skuVersion: sku ? pinCatalogVersion(sku.skuId, sku.version) : '' })
            }}
            skus={skus}
            loading={loading}
            error={catalogError}
            disabled={loading || Boolean(catalogError)}
          />
          {reward.skuVersion && !skuPin && <StateBanner tone="warn" title="已冻结 SKU 版本无法对照当前目录" detail={reward.skuVersion} />}
          {errorOf(errors, `${prefix}.skuVersion`) && <small className="referral-field-error">{errorOf(errors, `${prefix}.skuVersion`)}</small>}
        </div>
      </div>
      <Button className="referral-remove" onClick={() => onRemove(index)}><Trash2 size={14} />移除</Button>
    </div>
  )
}

function SimulationResultCard({ result }: { result: ReferralSimulationResult }) {
  const authorityOk = result.simulationOnly && result.evidenceAuthority === 'SIMULATED_INPUT'
  return (
    <div className="referral-sim-result" role="status">
      <Badge tone={authorityOk ? 'info' : 'warn'}>{result.evidenceAuthority || '权威待确认'}</Badge>
      <p><strong>{qualificationStateLabel(result.qualification.state)}</strong></p>
      <p>{qualificationReasonLabel(result.qualification.reason)} · 假设人数 {result.validCount}</p>
      {result.qualification.dueAt && <p>观察截止 {result.qualification.dueAt}</p>}
      <p className="referral-copy">仿真结果，不会实际发奖。候选不是到账，也不是 HELD 受理。</p>
      {result.rewardCandidates.length === 0 ? <p className="muted">没有奖励候选。</p> : (
        <ul>
          {result.rewardCandidates.map((item) => (
            <li key={`${item.rule.ruleId}-${item.milestoneKey}`}>
              {item.rule.ruleId} · {item.rule.role} · {item.rule.mode} · milestone {item.milestoneKey}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
