import { useEffect, useRef, useState } from 'react'
import { ArrowDown, ArrowUp, FlaskConical, Plus, ShieldCheck, Trash2 } from 'lucide-react'
import { Select } from '../../components/Select'
import { RecordTable } from '../../components/RecordTable'
import { Badge, Button, EmptyState, PageHeader, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { api } from '../../shared/api/client'
import { useAuth } from '../../shared/auth/useAuth'
import { DMN_TYPES, type DmnAnalysis, type DmnColumn, type DmnEvaluation, type DmnTable, type DmnType } from '../../shared/api/dmn'

const DIRECTION = { INPUTS: 'inputs', OUTPUTS: 'outputs' } as const
const DIRECTIONS = [DIRECTION.INPUTS, DIRECTION.OUTPUTS]
const MAX_COLUMNS = 4
const MAX_RULES = 32
const TEXT = {
  empty: '请填写字段名称和每个规则单元格；不限制的输入使用 -。',
  duplicate: '字段名须以字母或下划线开头，仅含字母、数字、下划线，最多 32 字符，且不能重复或使用引擎保留名称。',
  failed: '请求失败，请检查输入后重试。',
}
const TYPE_LABEL: Record<DmnType, string> = { number: '数字', string: '文本', boolean: '布尔' }
const RESERVED_NAMES = new Set(['DmnWorkbenchModel', 'DmnWorkbenchDecision'])
const initialTable = (): DmnTable => ({ hitPolicy: 'UNIQUE', inputs: [{ name: '', type: 'number' }], outputs: [{ name: '', type: 'string' }], rules: [{ inputs: [''], outputs: [''] }] })

/** 草稿与服务端计算结果分离；编辑即废弃旧结果，迟到响应不能覆盖新输入。 */
export function DmnDesignerPage() {
  const auth = useAuth()
  const canAnalyze = auth.hasPermission('definition:compile')
  const canEvaluate = auth.hasPermission('definition:simulate')
  const [table, setTable] = useState<DmnTable>(initialTable)
  const [rowKeys, setRowKeys] = useState(() => [crypto.randomUUID()])
  const [context, setContext] = useState<string[]>([''])
  const [analysis, setAnalysis] = useState<DmnAnalysis>()
  const [evaluation, setEvaluation] = useState<DmnEvaluation>()
  const [busy, setBusy] = useState<'analyze' | 'evaluate'>()
  const [error, setError] = useState('')
  const revision = useRef(0)
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])

  const edit = (next: DmnTable) => {
    revision.current++
    setTable(next); setAnalysis(undefined); setEvaluation(undefined); setError('')
  }
  const changeColumn = (kind: 'inputs' | 'outputs', index: number, changes: Partial<DmnColumn>) => {
    edit({ ...table, [kind]: table[kind].map((column, i) => i === index ? { ...column, ...changes } : column) })
  }
  const addColumn = (kind: 'inputs' | 'outputs') => {
    if (table[kind].length >= MAX_COLUMNS) return
    edit({ ...table, [kind]: [...table[kind], { name: '', type: 'string' }], rules: table.rules.map((row) => ({ ...row, [kind]: [...row[kind], ''] })) })
    if (kind === DIRECTION.INPUTS) setContext([...context, ''])
  }
  const removeColumn = (kind: 'inputs' | 'outputs', index: number) => {
    if (table[kind].length <= 1) return
    edit({ ...table, [kind]: table[kind].filter((_, i) => i !== index), rules: table.rules.map((row) => ({ ...row, [kind]: row[kind].filter((_, i) => i !== index) })) })
    if (kind === DIRECTION.INPUTS) setContext(context.filter((_, i) => i !== index))
  }
  const moveRow = (index: number, offset: number) => {
    const next = [...table.rules]
    const target = index + offset
    if (target < 0 || target >= next.length) return
    ;[next[index], next[target]] = [next[target], next[index]]
    const keys = [...rowKeys]
    ;[keys[index], keys[target]] = [keys[target], keys[index]]
    setRowKeys(keys)
    edit({ ...table, rules: next })
  }
  const addRow = () => {
    if (table.rules.length >= MAX_RULES) return
    setRowKeys([...rowKeys, crypto.randomUUID()])
    edit({ ...table, rules: [...table.rules, { inputs: table.inputs.map(() => ''), outputs: table.outputs.map(() => '') }] })
  }
  const removeRow = (index: number) => {
    if (table.rules.length <= 1) return
    setRowKeys(rowKeys.filter((_, i) => i !== index))
    edit({ ...table, rules: table.rules.filter((_, i) => i !== index) })
  }
  const run = async (action: 'analyze' | 'evaluate') => {
    if (busy || (action === 'analyze' ? !canAnalyze : !canEvaluate)) return
    const names = [...table.inputs, ...table.outputs].map((column) => column.name)
    if (names.some((name) => !name.trim()) || table.rules.some((row) => [...row.inputs, ...row.outputs].some((cell) => !cell.trim()))) {
      setError(TEXT.empty); return
    }
    if (new Set(names).size !== names.length || names.some((name) => !/^[A-Za-z_][A-Za-z0-9_]{0,31}$/.test(name) || RESERVED_NAMES.has(name))) {
      setError(TEXT.duplicate); return
    }
    const current = revision.current
    setBusy(action); setError('')
    if (action === 'analyze') setAnalysis(undefined)
    else setEvaluation(undefined)
    try {
      if (action === 'analyze') {
        const result = await api.analyzeDmn(table)
        if (mounted.current && revision.current === current) setAnalysis(result)
      } else {
        const values = Object.fromEntries(table.inputs.map((column, i) => [column.name, context[i]]))
        const result = await api.evaluateDmn(table, values)
        if (mounted.current && revision.current === current) setEvaluation(result)
      }
    } catch (cause) {
      if (mounted.current && revision.current === current) setError(cause instanceof Error ? cause.message : TEXT.failed)
    } finally {
      if (mounted.current) setBusy(undefined)
    }
  }

  return <div className="workspace dmn-page">
    <PageHeader eyebrow="DMN_DECISION_TABLE" title="DMN 决策表" description="配置字段与规则，检查覆盖范围，再用测试输入执行真实引擎试算。"
      actions={<><Button disabled={!!busy || !canAnalyze} title={!canAnalyze ? '需要规则编译权限' : undefined} onClick={() => void run('analyze')}><ShieldCheck size={15} />{busy === 'analyze' ? '正在分析…' : '分析表格'}</Button><Button tone="primary" disabled={!!busy || !canEvaluate} title={!canEvaluate ? '需要规则试算权限' : undefined} onClick={() => void run('evaluate')}><FlaskConical size={15} />{busy === 'evaluate' ? '正在求值…' : '运行测试行'}</Button></>} />
    <StateBanner tone="info" title="未保存草稿" detail="当前支持真实分析和试算；版本保存及发布尚未接入。离开页面会丢失草稿，试算不会发放权益或发布规则。" />
    {(!canAnalyze || !canEvaluate) && <StateBanner tone="warn" title="部分操作不可用" detail={[!canAnalyze && '分析需要规则编译权限', !canEvaluate && '试算需要规则试算权限'].filter(Boolean).join('；')} />}
    {error && <StateBanner tone="error" title="未完成计算" detail={error} />}
    {busy && <StateBanner tone="info" title="引擎计算中" detail="可以继续编辑；编辑后的草稿会忽略此次旧结果，请等待本次结束后重新计算。" />}
    <Panel>
      <PanelHeader title="字段与命中策略" eyebrow="TABLE SCHEMA" />
      <div className="dmn-toolbar"><label>命中策略<Select aria-label="命中策略" value={table.hitPolicy} onChange={(event) => edit({ ...table, hitPolicy: event.target.value as DmnTable['hitPolicy'] })}><option value="UNIQUE">UNIQUE · 唯一命中</option><option value="FIRST">FIRST · 首条命中</option></Select></label><Badge tone="info">最多 32 条规则</Badge></div>
      <p className="dmn-help">字段名仅用字母、数字、下划线，以字母或下划线开头。UNIQUE 要求最多命中一条；FIRST 按表格顺序取首条。</p>
      {DIRECTIONS.map((kind) => <div className="dmn-column-group" key={kind}>
        <RecordTable label={kind === DIRECTION.INPUTS ? '输入字段' : '输出字段'} columns={['方向', '字段名', '类型', '操作']} actions>
          {table[kind].map((column, index) => <tr key={index}><td>{kind === DIRECTION.INPUTS ? '输入' : '输出'} {index + 1}</td><td><input aria-label={`${kind === DIRECTION.INPUTS ? '输入' : '输出'}字段 ${index + 1} 名称`} maxLength={32} value={column.name} onChange={(event) => changeColumn(kind, index, { name: event.target.value })} /></td><td><Select aria-label={`${kind === DIRECTION.INPUTS ? '输入' : '输出'}字段 ${index + 1} 类型`} value={column.type} onChange={(event) => changeColumn(kind, index, { type: event.target.value as DmnType })}>{DMN_TYPES.map((type) => <option key={type} value={type}>{TYPE_LABEL[type]}</option>)}</Select></td><td><Button tone="ghost" aria-label={`删除${kind === DIRECTION.INPUTS ? '输入' : '输出'}字段 ${index + 1}`} disabled={table[kind].length <= 1} onClick={() => removeColumn(kind, index)}><Trash2 size={14} />删除</Button></td></tr>)}
        </RecordTable>
        <Button tone="ghost" disabled={table[kind].length >= MAX_COLUMNS} onClick={() => addColumn(kind)}><Plus size={14} />新增{kind === DIRECTION.INPUTS ? '输入' : '输出'}字段</Button>
      </div>)}
    </Panel>
    <Panel>
      <PanelHeader title="规则表" eyebrow="RULES" aside={<Badge tone="info">{table.rules.length} / {MAX_RULES}</Badge>} />
      <p className="dmn-help" id="dmn-cell-help">输入条件：- 表示不限；数字可填 100、&gt;= 100；文本填带双引号的值，如 &quot;VIP&quot;；布尔填 true 或 false。输出只填同类型字面量，不支持公式。数字合计最多 18 位数字、6 位小数。</p>
      <div className="dmn-scroll" role="region" aria-label="规则编辑表格" tabIndex={0}>
        <table className="dmn-table" aria-label="规则编辑" style={{ minWidth: Math.max(720, (table.inputs.length + table.outputs.length) * 180 + 250) }}><thead><tr><th scope="col">#</th>{DIRECTIONS.flatMap((kind) => table[kind].map((column, index) => <th scope="col" key={`${kind}-${index}`} className={kind === DIRECTION.INPUTS ? 'input-column' : 'output-column'}><small>{kind === DIRECTION.INPUTS ? '输入' : '输出'} · {TYPE_LABEL[column.type]}</small>{column.name || `未命名字段 ${index + 1}`}</th>))}<th scope="col" className="dmn-row-actions">规则操作</th></tr></thead>
          <tbody>{table.rules.map((row, index) => <tr key={rowKeys[index]}><td>{index + 1}</td>{DIRECTIONS.flatMap((kind) => row[kind].map((cell, column) => <td key={`${kind}-${column}`}><input aria-label={`规则 ${index + 1} ${kind === DIRECTION.INPUTS ? '输入' : '输出'} ${column + 1}`} aria-describedby="dmn-cell-help" value={cell} maxLength={256} onChange={(event) => edit({ ...table, rules: table.rules.map((item, rowIndex) => rowIndex === index ? { ...item, [kind]: item[kind].map((value, cellIndex) => cellIndex === column ? event.target.value : value) } : item) })} /></td>))}<td className="dmn-row-actions"><div className="dmn-actions"><Button tone="ghost" aria-label={`上移规则 ${index + 1}`} disabled={index === 0} onClick={() => moveRow(index, -1)}><ArrowUp size={14} /></Button><Button tone="ghost" aria-label={`下移规则 ${index + 1}`} disabled={index === table.rules.length - 1} onClick={() => moveRow(index, 1)}><ArrowDown size={14} /></Button><Button tone="ghost" aria-label={`删除规则 ${index + 1}`} disabled={table.rules.length <= 1} onClick={() => removeRow(index)}><Trash2 size={14} /></Button></div></td></tr>)}</tbody>
        </table>
      </div>
      <Button tone="ghost" disabled={table.rules.length >= MAX_RULES} onClick={addRow}><Plus size={14} />新增规则行</Button>
    </Panel>
    <Panel>
      <PanelHeader eyebrow="STATIC ANALYSIS" title="Gap / Overlap" />
      {!analysis ? <EmptyState title="尚未分析" detail="填写规则后点击「分析表格」。修改表格后需要重新分析。" /> : <>
        <StateBanner tone={analysis.valid ? 'success' : 'warn'} title={analysis.valid ? '引擎分析通过' : '存在规则问题'} detail={`覆盖缺口 ${analysis.gaps.length} 项，重叠 ${analysis.overlaps.length} 项；请结合引擎消息判断当前策略。`} />
        <DmnMessages messages={Array.from(new Set([...analysis.gaps, ...analysis.overlaps, ...analysis.messages]))} empty="引擎未报告其他问题。" />
      </>}
    </Panel>
    <Panel>
      <PanelHeader eyebrow="TEST CASE" title="测试输入与结果" />
      <div className="test-row">{table.inputs.map((column, index) => <label key={index}>{column.name || `输入字段 ${index + 1}`}<input aria-label={`测试输入 ${index + 1}`} aria-describedby="dmn-context-help" value={context[index]} maxLength={256} onChange={(event) => { revision.current++; setContext(context.map((value, i) => i === index ? event.target.value : value)); setEvaluation(undefined); setError('') }} /></label>)}</div>
      <p className="dmn-help" id="dmn-context-help">按字段类型填写：数字用十进制，布尔用 true / false，文本直接填写，不加引号。空文本有效。结果中的数字保留精确十进制文本。</p>
      {!evaluation ? <EmptyState title="尚未求值" detail="填写测试输入后点击「运行测试行」。" /> : <>
        <StateBanner tone={evaluation.success ? 'success' : 'warn'} title={evaluation.success ? '引擎求值完成' : '求值未通过'} detail="以下为引擎返回的输入上下文与决策结果。无结果值（null）表示没有结果；带双引号的是文本值。" />
        <RecordTable label="引擎返回值" columns={['字段', '返回值']}>
          {Object.entries(evaluation.results).map(([name, value]) => <tr key={name}><td>{name}</td><td><code className="dmn-result-value">{value === null ? '无结果值（null）' : JSON.stringify(value)}</code></td></tr>)}
          {Object.keys(evaluation.results).length === 0 && <tr><td colSpan={2}>没有返回值</td></tr>}
        </RecordTable>
        <DmnMessages messages={evaluation.messages} empty="引擎未报告其他消息。" />
      </>}
    </Panel>
  </div>
}

function DmnMessages({ messages, empty }: { messages: string[]; empty: string }) {
  return messages.length ? <ul className="dmn-messages">{messages.map((message, index) => <li key={index}>{message}</li>)}</ul> : <p className="dmn-help">{empty}</p>
}
