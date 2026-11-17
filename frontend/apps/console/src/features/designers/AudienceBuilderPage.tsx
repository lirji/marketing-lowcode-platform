import { useSearchParams } from 'react-router-dom'
import { RecordTable } from '../../components/RecordTable'
import { DateTimePicker } from '../../components/DateTimePicker'
import { FieldHint } from '../../components/FieldHint'
import { Select } from '../../components/Select'
import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Eye, Plus, Save, ShieldCheck, Trash2 } from 'lucide-react'
import { Badge, Button, DemoBanner, EmptyState, PageHeader, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { api } from '../../shared/api/client'
import { problemDetail } from '../../shared/api/problem'
import { useAuth } from '../../shared/auth/useAuth'
import type { AudienceView, FieldDefinition } from '../../shared/api/schemas'

type Condition = { id: number; field: string; operator: string; value: string }

const UI_OPERATORS = ['=', '!=', '>', '>=', '<', '<=', 'IN'] as const
const ORDERED_VALUE_TYPES: ReadonlySet<string> = new Set(['DECIMAL', 'INSTANT'])
function allowedOperators(valueType?: string): readonly string[] {
  return valueType && ORDERED_VALUE_TYPES.has(valueType) ? UI_OPERATORS : ['=', '!=', 'IN']
}
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
  const [params] = useSearchParams()
  const auth = useAuth()
  const selectedId = params.get('segmentId')?.trim() || ''
  return <AudienceEditor key={`${auth.tenantId}:${auth.subject}:${selectedId}`} selectedId={selectedId} />
}

function AudienceEditor({ selectedId }: { selectedId: string }) {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const [name, setName] = useState('')
  const [segmentId, setSegmentId] = useState('')
  const [version, setVersion] = useState<number>()
  const [conditions, setConditions] = useState<Condition[]>([])
  const [match, setMatch] = useState<'ALL' | 'ANY'>('ALL')
  const [notice, setNotice] = useState('')
  const [selectionError, setSelectionError] = useState('')
  const [hydrated, setHydrated] = useState(false)
  const canWrite = auth.hasPermission('audience:write')
  const audiences = useQuery({ queryKey: ['audiences'], queryFn: api.audiences, enabled: !api.demoMode && auth.hasPermission('audience:read') })
  const fields = useQuery({ queryKey: ['fields'], queryFn: api.fields, enabled: !api.demoMode && auth.hasPermission('audience-field:read') })
  const fieldOptions = fieldOptionsFrom(fields.data)
  const resetDraft = () => {
    setSegmentId('')
    setVersion(undefined)
    setName('')
    setMatch('ALL')
    setConditions([])
  }
  const applySegment = (item: AudienceView) => {
    setSelectionError('')
    setSegmentId(item.segmentId)
    setVersion(item.version)
    setName(item.name)
    setMatch(item.rule?.match === 'ANY' ? 'ANY' : 'ALL')
    setConditions(fromAudience(item))
  }

  useEffect(() => {
    if (hydrated || api.demoMode) {
      if (api.demoMode && !hydrated) setHydrated(true)
      return
    }
    if (audiences.isPending || audiences.isError) return
    const selected = selectedId ? audiences.data?.find((item) => item.segmentId === selectedId) : audiences.data?.[0]
    if (selected) applySegment(selected)
    else if (selectedId) setSelectionError('所选人群不存在或当前身份无权访问，请从已保存版本中重新选择。')
    setHydrated(true)
  }, [audiences.data, audiences.isPending, audiences.isError, hydrated, selectedId])

  const update = (id: number, key: keyof Condition, value: string) => setConditions((items) => items.map((item) => {
    if (item.id !== id) return item
    const updated = { ...item, [key]: value }
    if (key === 'field' && !allowedOperators(fields.data?.find((field) => field.fieldId === value)?.valueType).includes(item.operator)) updated.operator = '='
    return updated
  }))
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
  const fieldMeta = (fieldId: string) => fields.data?.find((item) => item.fieldId === fieldId)

  return <div className="workspace audience-page">
    <PageHeader eyebrow={version ? `AUDIENCE_EXPRESSION · v${version}` : 'AUDIENCE_EXPRESSION'} title={name.trim() || '新人群'} description="保存圈选规则与字段用途；真实人群的计算需要已接入的画像来源。" actions={<><Button disabled title="画像来源接入后可预估真实人群"><Eye size={15} />预估人数</Button><Button disabled title="真实人群计算完成后可生成快照"><ShieldCheck size={15} />保存快照</Button>{!api.demoMode && canWrite && <Button tone="primary" disabled={save.isPending || !name.trim() || Boolean(selectionError)} onClick={() => save.mutate()}><Save size={15} />保存规则</Button>}</>} />
    <DemoBanner />
    {selectionError && <StateBanner tone="error" title="无法打开所选人群" detail={selectionError} />}
    {!api.demoMode && <StateBanner tone="info" title="画像来源尚未接入" detail="可以保存圈选规则；接入真实会员画像后才能预估人数和生成完整人群快照。" />}
    {notice && <StateBanner tone="success" title="已写入控制面" detail={notice} />}
    {audiences.isError && <StateBanner tone="error" title="人群加载失败" detail={problemDetail(audiences.error)} />}
    {fields.isError && <StateBanner tone="error" title="字段目录加载失败" detail={problemDetail(fields.error)} />}
    {save.isError && <StateBanner tone="error" title="保存失败" detail={problemDetail(save.error)} />}
    {!api.demoMode && <div className="form-grid audience-basics">
      <label className="field"><span>人群名称</span><input value={name} onChange={(event) => setName(event.target.value)} placeholder="未命名人群" /></label>
      <label className="field"><span>已保存版本</span>
        <Select aria-label="已保存版本" value={segmentId} onChange={(event) => {
          const item = (audiences.data ?? []).find((row) => row.segmentId === event.target.value)
          if (item) applySegment(item)
          else resetDraft()
        }}>
          <option value="">新人群（保存后分配）</option>
          {(audiences.data ?? []).map((item) => <option key={item.segmentId} value={item.segmentId}>{item.name} · {item.segmentId} v{item.version}</option>)}
        </Select>
      </label>
    </div>}
    <div className="builder-layout">
      <Panel className="condition-panel"><PanelHeader eyebrow="条件树" title="圈选规则" aside={<div className="segmented"><button className={match === 'ALL' ? 'active' : ''} onClick={() => setMatch('ALL')}>同时满足</button><button className={match === 'ANY' ? 'active' : ''} onClick={() => setMatch('ANY')}>任一满足</button></div>} />
        <div className="condition-group">
          <div className="group-rail"><span>{match === 'ALL' ? 'AND' : 'OR'}</span></div>
          <div className="condition-list">
            {conditions.length === 0 ? <EmptyState title="还没有圈选条件" detail={fieldOptions.length ? '从已注册字段添加条件，保存后才会写入控制面。' : '暂无已注册字段，字段目录加载完成前无法添加条件。'} /> : conditions.map((condition, index) => <div className="condition-row" key={condition.id}>
              <span>{index + 1}</span>
              <Select className="condition-field" aria-label={`条件 ${index + 1} 字段`} value={condition.field} onChange={(event) => update(condition.id, 'field', event.target.value)}>
                {fieldOptions.map((field) => <option key={field.id} value={field.id}>{field.label}</option>)}
                {fieldOptions.some((field) => field.id === condition.field) ? null : condition.field ? <option value={condition.field}>{condition.field}</option> : null}
              </Select>
              <Select className="condition-operator" aria-label={`条件 ${index + 1} 算子`} value={condition.operator} onChange={(event) => update(condition.id, 'operator', event.target.value)}>
                {allowedOperators(fieldMeta(condition.field)?.valueType).map((item) => <option key={item}>{item}</option>)}
                {!allowedOperators(fieldMeta(condition.field)?.valueType).includes(condition.operator) && <option value={condition.operator}>{condition.operator}（当前字段不支持）</option>}
              </Select>
              <div className="condition-value">
                {fieldMeta(condition.field)?.valueType === 'INSTANT' ? <div className="condition-instants">
                  {(condition.operator === 'IN' ? condition.value.split(',') : [condition.value]).map((instant, valueIndex, values) => <div className="instant-entry" key={valueIndex}>
                    <DateTimePicker label={`条件 ${index + 1} 时间 ${valueIndex + 1}`} describedBy={`condition-${condition.id}-hint`} value={instant.trim()} onChange={(value) => update(condition.id, 'value', values.map((existing, i) => i === valueIndex ? value : existing).join(','))} />
                    {condition.operator === 'IN' && values.length > 1 && <button type="button" aria-label={`删除条件 ${index + 1} 时间 ${valueIndex + 1}`} onClick={() => update(condition.id, 'value', values.filter((_, i) => i !== valueIndex).join(','))}><Trash2 size={14} /></button>}
                  </div>)}
                  {condition.operator === 'IN' && <button className="row-action" type="button" onClick={() => update(condition.id, 'value', `${condition.value},`)}>添加时间</button>}
                </div> : <input aria-label={`条件 ${index + 1} 值`} aria-describedby={`condition-${condition.id}-hint`} value={condition.value} onChange={(event) => update(condition.id, 'value', event.target.value)} />}
              </div>
              <Badge tone={fieldMeta(condition.field)?.classification === 'PERSONAL' || fieldMeta(condition.field)?.classification === 'SENSITIVE' ? 'warn' : 'neutral'}>{fieldAge(fieldMeta(condition.field)?.maxAgeSeconds)}</Badge>
              <button aria-label={`删除条件 ${index + 1}`} onClick={() => setConditions((items) => items.filter((item) => item.id !== condition.id))}><Trash2 size={14} /></button>
              <FieldHint id={`condition-${condition.id}-hint`}>{fieldMeta(condition.field)?.valueType === 'INSTANT' ? '按北京时间选择；多个时间表示满足其中任意一个。' : condition.operator === 'IN' ? '多个候选值用英文逗号分隔，例如 GOLD,PLUS。' : fieldMeta(condition.field)?.valueType === 'BOOLEAN' ? '填写 true（是）或 false（否）。' : fieldMeta(condition.field)?.valueType === 'DECIMAL' ? '填写数值，按所选字段的业务单位计算。' : '填写与所选字段匹配的值，例如会员等级 GOLD。'}</FieldHint>
            </div>)}
          </div>
        </div>
        <Button tone="ghost" disabled={!fieldOptions[0]?.id} title={fieldOptions[0]?.id ? '添加条件' : '暂无已注册字段'} onClick={() => {
          const fieldId = fieldOptions[0]?.id
          if (!fieldId) return
          setConditions((items) => [...items, { id: Date.now(), field: fieldId, operator: '=', value: '' }])
        }}><Plus size={14} />添加条件</Button>
        <p className="muted">排除集尚未接入控制面，不会显示虚构快照。</p>
      </Panel>
      <aside className="audience-aside">
        <Panel><PanelHeader eyebrow="PREVIEW" title="人群预估" aside={<Badge tone="warn">待接入数据源</Badge>} /><p>暂无可验证的真实人数。规则已保存不代表全量人群已完成计算。</p></Panel>
        <Panel><PanelHeader eyebrow="DATA GOVERNANCE" title="字段来源与用途" /><LiveProvenance fields={fields.data ?? []} used={conditions.map((item) => item.field)} /></Panel>
      </aside>
    </div>
  </div>
}

function LiveProvenance({ fields, used }: { fields: FieldDefinition[]; used: string[] }) {
  const rows = used.map((fieldId) => fields.find((item) => item.fieldId === fieldId) ?? { fieldId, classification: 'UNKNOWN' })
  if (rows.length === 0) return <EmptyState title="没有引用字段" detail="添加条件后显示字段治理信息。" />
  return <RecordTable label="字段来源" columns={['字段 ID', '所属方', '分类', '最大有效期', '数据来源']}>{rows.map((item) => <tr key={item.fieldId}><td>{item.fieldId}</td><td>{item.owner ?? '—'}</td><td><Badge tone={item.classification === 'PERSONAL' || item.classification === 'SENSITIVE' ? 'warn' : 'good'}>{item.classification ?? '—'}</Badge></td><td>{fieldAge(item.maxAgeSeconds)}</td><td>{item.provenance ?? '已注册'}</td></tr>)}</RecordTable>
}
