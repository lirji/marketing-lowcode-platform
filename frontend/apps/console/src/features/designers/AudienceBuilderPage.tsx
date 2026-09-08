import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Clock3, Database, Eye, Plus, ShieldCheck, Trash2 } from 'lucide-react'
import { Badge, Button, DemoBanner, EmptyState, PageHeader, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { api } from '../../shared/api/client'
import { problemDetail } from '../../shared/api/problem'
import { useAuth } from '../../shared/auth/useAuth'
import type { AudienceView, FieldDefinition } from '../../shared/api/schemas'

type Condition = { id: number; field: string; operator: string; value: string }
type PreviewView = { estimatedCount: number; sampleSubjectTokens: string[] }

const UI_OPERATORS = ['=', '!=', '>', '>=', '<', '<=', 'IN'] as const
const TO_API: Record<string, string> = { '=': 'EQ', '!=': 'NE', '>': 'GT', '>=': 'GTE', '<': 'LT', '<=': 'LTE', IN: 'IN' }
const TO_UI: Record<string, string> = { EQ: '=', NE: '!=', GT: '>', GTE: '>=', LT: '<', LTE: '<=', IN: 'IN' }

function toApiOperator(value: string) {
  return TO_API[value] ?? value
}

function toUiOperator(value: string) {
  return TO_UI[value] ?? value
}

export function fromAudience(item: AudienceView): Condition[] {
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
  if (condition.operator === '!=') return condition.value
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

function fieldOptionsFrom(fields: FieldDefinition[] | undefined) {
  return (fields ?? []).map((item) => ({ id: item.fieldId, label: item.fieldId }))
}

export function AudienceBuilderPage() {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const [name, setName] = useState('')
  const [segmentId, setSegmentId] = useState('')
  const [version, setVersion] = useState<number>()
  const [conditions, setConditions] = useState<Condition[]>([])
  const [match, setMatch] = useState<'ALL' | 'ANY'>('ALL')
  const [previewed, setPreviewed] = useState(false)
  const [preview, setPreview] = useState<PreviewView | null>(null)
  const [notice, setNotice] = useState('')
  const [hydrated, setHydrated] = useState(false)
  const canWrite = auth.hasPermission('audience:write')
  const canPreview = auth.hasPermission('audience:preview')
  const canSnapshot = auth.hasPermission('audience:snapshot')
  const audiences = useQuery({ queryKey: ['audiences'], queryFn: api.audiences, enabled: !api.demoMode && auth.hasPermission('audience:read') })
  const fields = useQuery({ queryKey: ['fields'], queryFn: api.fields, enabled: !api.demoMode && auth.hasPermission('audience-field:read') })
  const fieldOptions = fieldOptionsFrom(fields.data)
  const resetDraft = () => {
    setSegmentId('')
    setVersion(undefined)
    setName('')
    setMatch('ALL')
    setConditions([])
    setPreview(null)
    setPreviewed(false)
  }
  const applySegment = (item: AudienceView) => {
    setSegmentId(item.segmentId)
    setVersion(item.version)
    setName(item.name)
    setMatch(item.rule?.match === 'ANY' ? 'ANY' : 'ALL')
    setConditions(fromAudience(item))
    setPreview(null)
    setPreviewed(false)
  }

  useEffect(() => {
    if (hydrated || api.demoMode) {
      if (api.demoMode && !hydrated) setHydrated(true)
      return
    }
    if (audiences.isPending) return
    const first = audiences.data?.[0]
    if (first) applySegment(first)
    setHydrated(true)
  }, [audiences.data, audiences.isPending, hydrated])

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
  const fieldMeta = (fieldId: string) => fields.data?.find((item) => item.fieldId === fieldId)

  return <div className="workspace audience-page">
    <PageHeader eyebrow={version ? `AUDIENCE_EXPRESSION · v${version}` : 'AUDIENCE_EXPRESSION'} title={name.trim() || '新人群'} description="规则、来源、新鲜度和隐私策略共同决定可用人群，不接受请求端伪造标签。" actions={<><Button onClick={() => previewMutation.mutate()} disabled={api.demoMode || !canPreview || !segmentId || version == null || previewMutation.isPending} title={api.demoMode ? '演示模式不预估' : !segmentId ? '请先保存人群' : '按当前条件生成样本，命中以已保存版本为准'}><Eye size={15} />预估人数</Button><Button tone="primary" disabled={api.demoMode || !canSnapshot || !preview?.sampleSubjectTokens.length || snapshot.isPending} title={api.demoMode ? '演示模式不可执行' : '用预览样本写入快照'} onClick={() => snapshot.mutate()}><ShieldCheck size={15} />保存快照</Button></>} />
    <DemoBanner />
    {!api.demoMode && <StateBanner tone="info" title="控制面预览按提交样本评估" detail="不是全量人口普查。预估与快照使用已保存版本；修改条件后请先保存。" />}
    {notice && <StateBanner tone="success" title="已写入控制面" detail={notice} />}
    {audiences.isError && <StateBanner tone="error" title="人群加载失败" detail={problemDetail(audiences.error)} />}
    {fields.isError && <StateBanner tone="error" title="字段目录加载失败" detail={problemDetail(fields.error)} />}
    {save.isError && <StateBanner tone="error" title="保存失败" detail={problemDetail(save.error)} />}
    {previewMutation.isError && <StateBanner tone="error" title="预估失败" detail={problemDetail(previewMutation.error)} />}
    {snapshot.isError && <StateBanner tone="error" title="快照失败" detail={problemDetail(snapshot.error)} />}
    {!api.demoMode && <div className="form-grid">
      <label className="field"><span>人群名称</span><input value={name} onChange={(event) => setName(event.target.value)} placeholder="未命名人群" /></label>
      <label className="field"><span>已保存版本</span>
        <select aria-label="已保存版本" value={segmentId} onChange={(event) => {
          const item = (audiences.data ?? []).find((row) => row.segmentId === event.target.value)
          if (item) applySegment(item)
          else resetDraft()
        }}>
          <option value="">新人群（保存后分配）</option>
          {(audiences.data ?? []).map((item) => <option key={item.segmentId} value={item.segmentId}>{item.name} · {item.segmentId} v{item.version}</option>)}
        </select>
      </label>
      {canWrite && <div className="field"><span>&nbsp;</span><Button tone="primary" disabled={save.isPending || !name.trim()} onClick={() => save.mutate()}>保存规则</Button></div>}
    </div>}
    <div className="builder-layout">
      <Panel className="condition-panel"><PanelHeader eyebrow="条件树" title="圈选规则" aside={<div className="segmented"><button className={match === 'ALL' ? 'active' : ''} onClick={() => setMatch('ALL')}>同时满足</button><button className={match === 'ANY' ? 'active' : ''} onClick={() => setMatch('ANY')}>任一满足</button></div>} />
        <div className="condition-group"><div className="group-rail"><span>{match === 'ALL' ? 'AND' : 'OR'}</span></div><div className="condition-list">{conditions.length === 0 ? <EmptyState title="还没有圈选条件" detail={fieldOptions.length ? '从已注册字段添加条件，保存后才会写入控制面。' : '暂无已注册字段，字段目录加载完成前无法添加条件。'} /> : conditions.map((condition, index) => <div className="condition-row" key={condition.id}><span>{index + 1}</span><select aria-label={`条件 ${index + 1} 字段`} value={condition.field} onChange={(event) => update(condition.id, 'field', event.target.value)}>{fieldOptions.map((field) => <option key={field.id} value={field.id}>{field.label}</option>)}{fieldOptions.some((field) => field.id === condition.field) ? null : condition.field ? <option value={condition.field}>{condition.field}</option> : null}</select><select aria-label={`条件 ${index + 1} 算子`} value={condition.operator} onChange={(event) => update(condition.id, 'operator', event.target.value)}>{UI_OPERATORS.map((item) => <option key={item}>{item}</option>)}</select><input aria-label={`条件 ${index + 1} 值`} value={condition.value} onChange={(event) => update(condition.id, 'value', event.target.value)} /><Badge tone={fieldMeta(condition.field)?.classification === 'PERSONAL' || fieldMeta(condition.field)?.classification === 'SENSITIVE' ? 'warn' : 'neutral'}>{fieldAge(fieldMeta(condition.field)?.maxAgeSeconds)}</Badge><button aria-label={`删除条件 ${index + 1}`} onClick={() => setConditions((items) => items.filter((item) => item.id !== condition.id))}><Trash2 size={14} /></button></div>)}</div></div>
        <Button tone="ghost" disabled={!fieldOptions[0]?.id} title={fieldOptions[0]?.id ? '添加条件' : '暂无已注册字段'} onClick={() => {
          const fieldId = fieldOptions[0]?.id
          if (!fieldId) return
          setConditions((items) => [...items, { id: Date.now(), field: fieldId, operator: '=', value: '' }])
        }}><Plus size={14} />添加条件</Button>
        <p className="muted">排除集尚未接入控制面，不会显示虚构快照。</p>
      </Panel>
      <aside className="audience-aside">
        <Panel><PanelHeader eyebrow="PREVIEW" title="人群预估" aside={<Badge tone="info">样本评估</Badge>} />{previewed && preview ? <><strong className="audience-number">{preview.estimatedCount}</strong><p className="muted">控制面样本命中 {preview.estimatedCount}；主体 {preview.sampleSubjectTokens.join('、') || '无'}</p></> : <p>保存规则后预估。人数来自控制面样本评估，不是演示人口。</p>}</Panel>
        <Panel><PanelHeader eyebrow="DATA GOVERNANCE" title="字段来源与用途" /><LiveProvenance fields={fields.data ?? []} used={conditions.map((item) => item.field)} /></Panel>
      </aside>
    </div>
  </div>
}

function LiveProvenance({ fields, used }: { fields: FieldDefinition[]; used: string[] }) {
  const rows = used.map((fieldId) => fields.find((item) => item.fieldId === fieldId) ?? { fieldId, classification: 'UNKNOWN' })
  if (rows.length === 0) return <EmptyState title="没有引用字段" detail="添加条件后显示字段治理信息。" />
  return <ul className="provenance-list">{rows.map((item) => <li key={item.fieldId}>{item.classification === 'PERSONAL' || item.classification === 'SENSITIVE' ? <Clock3 size={14} /> : <Database size={14} />}<div><strong>{item.fieldId}</strong><small>{item.owner ?? '—'} · {item.classification ?? '—'} · maxAge {fieldAge(item.maxAgeSeconds)}</small></div><Badge tone={item.classification === 'PERSONAL' || item.classification === 'SENSITIVE' ? 'warn' : 'good'}>{item.provenance ?? '已注册'}</Badge></li>)}</ul>
}
