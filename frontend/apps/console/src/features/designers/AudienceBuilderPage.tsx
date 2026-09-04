import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Clock3, Database, Eye, Plus, ShieldCheck, Trash2, UsersRound } from 'lucide-react'
import { Badge, Button, DemoBanner, EmptyState, PageHeader, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { api } from '../../shared/api/client'
import { problemDetail } from '../../shared/api/problem'
import { useAuth } from '../../shared/auth/useAuth'
import type { AudienceView, FieldDefinition } from '../../shared/api/schemas'

type Condition = { id: number; field: string; operator: string; value: string }
type PreviewView = { estimatedCount: number; sampleSubjectTokens: string[] }

const UI_OPERATORS = ['=', '!=', '>', '>=', '<', '<=', 'IN'] as const
const DEFAULT_FIELDS = [
  { id: 'member.level', label: '会员等级' },
  { id: 'profile.cityTier', label: '城市等级' },
  { id: 'order.appliance90d', label: '近 90 天家电订单' },
  { id: 'event.cart7d', label: '近 7 天加购次数' },
]
const DEFAULT_CONDITIONS: Condition[] = [
  { id: 1, field: 'member.level', operator: '=', value: 'PLUS' },
  { id: 2, field: 'profile.cityTier', operator: 'IN', value: '1,2' },
  { id: 3, field: 'order.appliance90d', operator: '>=', value: '1' },
]

const TO_API: Record<string, string> = { '=': 'EQ', '!=': 'NE', '>': 'GT', '>=': 'GTE', '<': 'LT', '<=': 'LTE', IN: 'IN' }
const TO_UI: Record<string, string> = { EQ: '=', NE: '!=', GT: '>', GTE: '>=', LT: '<', LTE: '<=', IN: 'IN' }

function toApiOperator(value: string) {
  return TO_API[value] ?? value
}

function toUiOperator(value: string) {
  return TO_UI[value] ?? value
}

function fromAudience(item: AudienceView): Condition[] {
  return (item.rule?.conditions ?? []).map((condition, index) => ({
    id: index + 1,
    field: condition.fieldId,
    operator: toUiOperator(condition.operator),
    value: condition.value,
  }))
}

function toRule(match: 'ALL' | 'ANY', conditions: Condition[]) {
  return {
    match,
    conditions: conditions.map((condition) => ({
      fieldId: condition.field,
      operator: toApiOperator(condition.operator),
      value: condition.value,
    })),
  }
}

function matchingValue(condition: Condition) {
  if (condition.operator === 'IN') return condition.value.split(',')[0]?.trim() || '1'
  if (condition.operator === '>') {
    const number = Number(condition.value)
    return Number.isFinite(number) ? String(number + 1) : condition.value
  }
  if (condition.operator === '<') {
    const number = Number(condition.value)
    return Number.isFinite(number) ? String(number - 1) : condition.value
  }
  if (condition.operator === '!=') return `${condition.value}-other`
  return condition.value
}

function missingValue(condition: Condition) {
  if (condition.operator === 'IN') return '__none__'
  if (condition.operator === '!=' ) return condition.value
  const number = Number(condition.value)
  if (Number.isFinite(number) && ['>=', '>', '<=', '<'].includes(condition.operator)) {
    return String(condition.operator.startsWith('>') ? number - 1 : number + 1)
  }
  return `${condition.value}-miss`
}

function sampleProfiles(conditions: Condition[]) {
  const hit = Object.fromEntries(conditions.map((condition) => [condition.field, matchingValue(condition)]))
  const miss = Object.fromEntries(conditions.map((condition) => [condition.field, missingValue(condition)]))
  return [
    { subjectToken: 'sample-hit-1', attributes: hit },
    { subjectToken: 'sample-hit-2', attributes: hit },
    { subjectToken: 'sample-hit-3', attributes: hit },
    { subjectToken: 'sample-miss-1', attributes: miss },
    { subjectToken: 'sample-miss-2', attributes: miss },
    { subjectToken: 'sample-miss-3', attributes: miss },
  ]
}

function fieldAge(seconds?: number) {
  if (seconds == null) return '—'
  if (seconds < 60) return `${seconds}s`
  if (seconds < 3600) return `${Math.round(seconds / 60)}m`
  return `${Math.round(seconds / 3600)}h`
}

export function AudienceBuilderPage() {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const [name, setName] = useState('PLUS 家电高意向人群')
  const [segmentId, setSegmentId] = useState('')
  const [version, setVersion] = useState<number>()
  const [conditions, setConditions] = useState<Condition[]>(DEFAULT_CONDITIONS)
  const [match, setMatch] = useState<'ALL' | 'ANY'>('ALL')
  const [previewed, setPreviewed] = useState(api.demoMode)
  const [preview, setPreview] = useState<PreviewView | null>(null)
  const [notice, setNotice] = useState('')
  const [hydrated, setHydrated] = useState(false)
  const canWrite = auth.hasPermission('audience:write')
  const canPreview = auth.hasPermission('audience:preview')
  const canSnapshot = auth.hasPermission('audience:snapshot')
  const audiences = useQuery({ queryKey: ['audiences'], queryFn: api.audiences, enabled: !api.demoMode && auth.hasPermission('audience:read') })
  const fields = useQuery({ queryKey: ['fields'], queryFn: api.fields, enabled: !api.demoMode && auth.hasPermission('audience-field:read') })
  const fieldOptions = (fields.data?.length ? fields.data.map((item) => ({ id: item.fieldId, label: item.fieldId })) : DEFAULT_FIELDS)
  const applySegment = (item: AudienceView) => {
    setSegmentId(item.segmentId)
    setVersion(item.version)
    setName(item.name)
    setMatch(item.rule?.match === 'ANY' ? 'ANY' : 'ALL')
    const next = fromAudience(item)
    setConditions(next.length > 0 ? next : DEFAULT_CONDITIONS)
    setPreview(null)
    setPreviewed(false)
  }

  useEffect(() => {
    if (hydrated || !audiences.data?.length) return
    const item = audiences.data[0]
    setSegmentId(item.segmentId)
    setVersion(item.version)
    setName(item.name)
    setMatch(item.rule?.match === 'ANY' ? 'ANY' : 'ALL')
    const next = fromAudience(item)
    setConditions(next.length > 0 ? next : DEFAULT_CONDITIONS)
    setPreview(null)
    setPreviewed(false)
    setHydrated(true)
  }, [audiences.data, hydrated])

  const update = (id: number, key: keyof Condition, value: string) => setConditions((items) => items.map((item) => item.id === id ? { ...item, [key]: value } : item))
  const save = useMutation({
    mutationFn: async () => {
      const id = segmentId || `seg-${crypto.randomUUID().slice(0, 8)}`
      return api.createAudience({ segmentId: id, name, rule: toRule(match, conditions) })
    },
    onSuccess: (value) => {
      setSegmentId(value.segmentId)
      setVersion(value.version)
      setNotice(`已写入 ${value.segmentId} v${value.version}`)
      void queryClient.invalidateQueries({ queryKey: ['audiences'] })
    },
  })
  const previewMutation = useMutation({
    mutationFn: async () => {
      if (!segmentId || version == null) throw new Error('请先保存人群')
      return api.previewAudience(segmentId, version, sampleProfiles(conditions))
    },
    onSuccess: (value) => {
      setPreview({ estimatedCount: value.estimatedCount, sampleSubjectTokens: value.sampleSubjectTokens })
      setPreviewed(true)
    },
  })
  const snapshot = useMutation({
    mutationFn: async () => {
      if (!segmentId || version == null) throw new Error('请先保存人群')
      const tokens = preview?.sampleSubjectTokens ?? []
      if (tokens.length === 0) throw new Error('请先预估并得到样本主体')
      const asOf = new Date().toISOString()
      return api.snapshotAudience(segmentId, version, {
        subjectTokens: tokens,
        asOf,
        watermark: asOf,
        expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
      })
    },
    onSuccess: (value) => setNotice(`快照 ${value.snapshotId} 已写入，成员 ${value.memberCount ?? preview?.sampleSubjectTokens.length ?? 0}`),
  })

  return <div className="workspace audience-page">
    <PageHeader eyebrow={version ? `AUDIENCE_EXPRESSION · v${version}` : 'AUDIENCE_EXPRESSION · Draft v6'} title={name} description="规则、来源、新鲜度和隐私策略共同决定可用人群，不接受请求端伪造标签。" actions={<><Button onClick={() => api.demoMode ? setPreviewed(true) : previewMutation.mutate()} disabled={api.demoMode ? false : !canPreview || !segmentId || version == null || previewMutation.isPending} title={api.demoMode ? '演示预估' : !segmentId ? '请先保存人群' : '按当前条件生成样本，命中以已保存版本为准'}><Eye size={15} />预估人数</Button><Button tone="primary" disabled={api.demoMode || !canSnapshot || !preview?.sampleSubjectTokens.length || snapshot.isPending} title={api.demoMode ? '演示模式不可执行' : '用预览样本写入快照'} onClick={() => snapshot.mutate()}><ShieldCheck size={15} />保存快照</Button></>} />
    <DemoBanner />
    {!api.demoMode && <StateBanner tone="info" title="控制面预览按提交样本评估" detail="不是全量人口普查。预估与快照使用已保存版本；修改条件后请先保存。" />}
    {notice && <StateBanner tone="success" title="已写入控制面" detail={notice} />}
    {audiences.isError && <StateBanner tone="error" title="人群加载失败" detail={problemDetail(audiences.error)} />}
    {save.isError && <StateBanner tone="error" title="保存失败" detail={problemDetail(save.error)} />}
    {previewMutation.isError && <StateBanner tone="error" title="预估失败" detail={problemDetail(previewMutation.error)} />}
    {snapshot.isError && <StateBanner tone="error" title="快照失败" detail={problemDetail(snapshot.error)} />}
    {!api.demoMode && <div className="form-grid">
      <label className="field"><span>人群名称</span><input value={name} onChange={(event) => setName(event.target.value)} /></label>
      <label className="field"><span>已保存版本</span>
        <select value={segmentId} onChange={(event) => {
          const item = (audiences.data ?? []).find((row) => row.segmentId === event.target.value)
          if (item) applySegment(item)
          else {
            setSegmentId('')
            setVersion(undefined)
          }
        }}>
          <option value="">新人群（保存后分配）</option>
          {(audiences.data ?? []).map((item) => <option key={item.segmentId} value={item.segmentId}>{item.name} · {item.segmentId} v{item.version}</option>)}
        </select>
      </label>
      {canWrite && <div className="field"><span>&nbsp;</span><Button tone="primary" disabled={save.isPending || !name.trim()} onClick={() => save.mutate()}>保存规则</Button></div>}
    </div>}
    <div className="builder-layout">
      <Panel className="condition-panel"><PanelHeader eyebrow="条件树" title="圈选规则" aside={<div className="segmented"><button className={match === 'ALL' ? 'active' : ''} onClick={() => setMatch('ALL')}>同时满足</button><button className={match === 'ANY' ? 'active' : ''} onClick={() => setMatch('ANY')}>任一满足</button></div>} />
        <div className="condition-group"><div className="group-rail"><span>{match === 'ALL' ? 'AND' : 'OR'}</span></div><div className="condition-list">{conditions.map((condition, index) => <div className="condition-row" key={condition.id}><span>{index + 1}</span><select aria-label={`条件 ${index + 1} 字段`} value={condition.field} onChange={(event) => update(condition.id, 'field', event.target.value)}>{fieldOptions.map((field) => <option key={field.id} value={field.id}>{field.label}</option>)}{fieldOptions.some((field) => field.id === condition.field) ? null : <option value={condition.field}>{condition.field}</option>}</select><select aria-label={`条件 ${index + 1} 算子`} value={condition.operator} onChange={(event) => update(condition.id, 'operator', event.target.value)}>{UI_OPERATORS.map((item) => <option key={item}>{item}</option>)}</select><input aria-label={`条件 ${index + 1} 值`} value={condition.value} onChange={(event) => update(condition.id, 'value', event.target.value)} /><Badge tone={condition.field.startsWith('profile') ? 'warn' : 'good'}>{condition.field.startsWith('profile') ? '15m' : '实时'}</Badge><button aria-label={`删除条件 ${index + 1}`} onClick={() => setConditions((items) => items.filter((item) => item.id !== condition.id))}><Trash2 size={14} /></button></div>)}</div></div>
        <Button tone="ghost" onClick={() => setConditions((items) => [...items, { id: Date.now(), field: fieldOptions[0]?.id ?? 'event.cart7d', operator: '>=', value: '2' }])}><Plus size={14} />添加条件</Button>
        <div className="set-operation"><span>集合运算</span><button type="button" disabled={!api.demoMode} title={api.demoMode ? undefined : '排除集尚未接入控制面'}><UsersRound size={14} />排除「近 30 天已退款用户」<Badge>Snapshot v12</Badge></button></div>
      </Panel>
      <aside className="audience-aside">
        <Panel><PanelHeader eyebrow="PREVIEW" title="人群预估" aside={<Badge tone="good">{api.demoMode ? '水位 12s' : '样本评估'}</Badge>} />{api.demoMode && previewed ? <><strong className="audience-number">1,248,620</strong><p className="muted">约占可营销会员的 8.4%</p><div className="audience-bars"><div><span>一线城市</span><i><b style={{ width: '78%' }} /></i><strong>42%</strong></div><div><span>二线城市</span><i><b style={{ width: '61%' }} /></i><strong>36%</strong></div><div><span>其他</span><i><b style={{ width: '35%' }} /></i><strong>22%</strong></div></div><StateBanner tone="info" title="抽样置信度 98%" detail="基于 5% 稳定 hash 样本；非最终快照人数。" /></> : !api.demoMode && previewed && preview ? <><strong className="audience-number">{preview.estimatedCount}</strong><p className="muted">样本命中 {preview.estimatedCount} / 6；主体 {preview.sampleSubjectTokens.join('、') || '无'}</p></> : <p>修改规则后重新预估。</p>}</Panel>
        <Panel><PanelHeader eyebrow="DATA GOVERNANCE" title="字段来源与用途" />{api.demoMode ? <DemoProvenance /> : <LiveProvenance fields={fields.data ?? []} used={conditions.map((item) => item.field)} />}</Panel>
      </aside>
    </div>
  </div>
}

function DemoProvenance() {
  return <ul className="provenance-list"><li><Database size={14} /><div><strong>member.level</strong><small>会员域 · INTERNAL · maxAge 5m</small></div><Badge tone="good">允许营销</Badge></li><li><Clock3 size={14} /><div><strong>profile.cityTier</strong><small>画像平台 · PERSONAL · maxAge 15m</small></div><Badge tone="warn">需降级</Badge></li><li><Database size={14} /><div><strong>order.appliance90d</strong><small>订单域 · INTERNAL · maxAge 1h</small></div><Badge tone="good">允许营销</Badge></li></ul>
}

function LiveProvenance({ fields, used }: { fields: FieldDefinition[]; used: string[] }) {
  const rows = used.map((fieldId) => fields.find((item) => item.fieldId === fieldId) ?? { fieldId, classification: 'UNKNOWN' })
  if (rows.length === 0) return <EmptyState title="没有引用字段" detail="添加条件后显示字段治理信息。" />
  return <ul className="provenance-list">{rows.map((item) => <li key={item.fieldId}>{item.classification === 'PERSONAL' || item.classification === 'SENSITIVE' ? <Clock3 size={14} /> : <Database size={14} />}<div><strong>{item.fieldId}</strong><small>{item.owner ?? '—'} · {item.classification ?? '—'} · maxAge {fieldAge(item.maxAgeSeconds)}</small></div><Badge tone={item.classification === 'PERSONAL' || item.classification === 'SENSITIVE' ? 'warn' : 'good'}>{item.provenance ?? '已注册'}</Badge></li>)}</ul>
}
