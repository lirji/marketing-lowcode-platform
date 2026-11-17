import { useRef, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Button, Modal, StateBanner } from '../../components/ui'
import { RecordTable } from '../../components/RecordTable'
import { api } from '../../shared/api/client'
import { problemDetail } from '../../shared/api/problem'
import { useAuth } from '../../shared/auth/useAuth'
import type { JourneySimulationInput } from '../../shared/api/schemas'

type Pair = { id: string; name: string; value: string }
type Event = { id: string; offset: string; type: string; attributes: Pair[] }
const LIMIT = { EVENTS: 32, FIELDS: 32, KEY: 64, VALUE: 256, HORIZON: 604800 } as const
const STOP_LABEL = { COMPLETED: '模拟已到达结束节点', WAITING: '模拟仍在等待', FAILED: '模拟推进失败', TRUNCATED: '已达到仿真预算，结果不完整' } as const
const SIGNAL_LABEL = { START: '开始', EVENT: '输入事件', TIMER: '计时器' } as const
const COMMAND_LABEL = { SEND: '发送消息', GRANT: '发放权益', WEBHOOK: '调用Webhook' } as const
const newPair = (): Pair => ({ id: crypto.randomUUID(), name: '', value: '' })
const validPairs = (pairs: Pair[]) => pairs.every((pair) => pair.name.trim() && pair.name.length <= LIMIT.KEY && pair.value.length <= LIMIT.VALUE)
  && new Set(pairs.map((pair) => pair.name)).size === pairs.length
const dictionary = (pairs: Pair[]) => Object.fromEntries(pairs.map((pair) => [pair.name, pair.value]))

function Variables({ label, pairs, onChange }: { label: string; pairs: Pair[]; onChange: (pairs: Pair[]) => void }) {
  return <fieldset className="journey-sim-fields"><legend>{label}</legend>
    {pairs.map((pair, index) => <div className="journey-sim-pair" key={pair.id}>
      <label className="field"><span>属性名</span><input aria-label={`${label} ${index + 1} 属性名`} maxLength={LIMIT.KEY} value={pair.name} onChange={(event) => onChange(pairs.map((item) => item.id === pair.id ? { ...item, name: event.target.value } : item))} /></label>
      <label className="field"><span>属性值</span><input aria-label={`${label} ${index + 1} 属性值`} maxLength={LIMIT.VALUE} value={pair.value} onChange={(event) => onChange(pairs.map((item) => item.id === pair.id ? { ...item, value: event.target.value } : item))} /></label>
      <Button aria-label={`删除${label} ${index + 1}`} onClick={() => onChange(pairs.filter((item) => item.id !== pair.id))}>删除</Button>
    </div>)}
    <Button disabled={pairs.length >= LIMIT.FIELDS} onClick={() => onChange([...pairs, newPair()])}>添加{label}</Button>
    {!validPairs(pairs) && <p className="field-hint">属性名必填且不可重复；值按文本比较，可为空。</p>}
  </fieldset>
}

/** 仿真只绑定当前校验版本；输入变化后不显示旧回执，也不会触发真实业务动作。 */
export function JourneySimulationDialog({ definitionId, version, onClose }: { definitionId: string; version: number; onClose: () => void }) {
  const auth = useAuth()
  const revision = useRef(0)
  const [initial, setInitial] = useState<Pair[]>([])
  const [events, setEvents] = useState<Event[]>([])
  const [horizon, setHorizon] = useState('3600')
  const mutation = useMutation({ mutationFn: (input: { request: JourneySimulationInput; revision: number }) => api.simulateJourney(definitionId, version, input.request) })
  const change = (action: () => void) => { revision.current++; action() }
  const updateEvent = (id: string, patch: Partial<Event>) => change(() => setEvents((rows) => rows.map((row) => row.id === id ? { ...row, ...patch } : row)))
  const horizonValue = Number(horizon)
  const valid = /^\d+$/.test(horizon) && Number.isSafeInteger(horizonValue) && horizonValue <= LIMIT.HORIZON && validPairs(initial)
    && events.every((event, index) => /^\d+$/.test(event.offset) && Number.isSafeInteger(Number(event.offset)) && Number(event.offset) <= horizonValue
      && (index === 0 || Number(event.offset) >= Number(events[index - 1].offset)) && event.type.trim() && validPairs(event.attributes))
  const current = mutation.variables?.revision === revision.current
  const result = current && !mutation.isPending ? mutation.data : undefined
  const commands = result?.steps.flatMap((step) => step.commands.map((command) => ({ ...command, sequence: step.sequence, offsetSeconds: step.offsetSeconds }))) ?? []
  return <Modal title={`Journey 仿真 · v${version}`} description={`定义 ${definitionId}。仅演示本次输入下的状态推进和拟生成指令，不会发送消息、发放权益或调用外部系统。`} onClose={onClose}>
    <div className="journey-sim-content">
      <p className="field-hint">时间从模拟开始计秒，输入事件按时间升序排列；同一时刻先执行计时器，再按列表顺序执行事件。未模拟生产调度延迟、状态保留期与外部资格。</p>
      <label className="field"><span>模拟窗口（秒）</span><input aria-label="模拟窗口（秒）" aria-describedby="journey-sim-horizon-help" type="number" min={0} max={LIMIT.HORIZON} value={horizon} onChange={(event) => change(() => setHorizon(event.target.value))} /><small id="journey-sim-horizon-help" className="field-hint">0–604800 秒（7天）；超出窗口的计时器保留为等待。</small></label>
      <Variables label="初始变量" pairs={initial} onChange={(pairs) => change(() => setInitial(pairs))} />
      <section><h3>事件序列</h3><p className="field-hint">最多32个事件。不添加事件时，仅推进开始信号和窗口内计时器。</p>
        {events.map((event, index) => <fieldset className="journey-sim-fields" key={event.id}><legend>事件 {index + 1}</legend>
          <div className="journey-sim-pair"><label className="field"><span>发生时间（相对秒）</span><input aria-label={`事件 ${index + 1} 发生时间（秒）`} type="number" min={0} max={horizonValue} value={event.offset} onChange={(e) => updateEvent(event.id, { offset: e.target.value })} /></label>
          <label className="field"><span>事件类型</span><input aria-label={`事件 ${index + 1} 类型`} maxLength={LIMIT.KEY} value={event.type} onChange={(e) => updateEvent(event.id, { type: e.target.value })} /></label>
          <Button onClick={() => change(() => setEvents((rows) => rows.filter((row) => row.id !== event.id)))}>删除事件 {index + 1}</Button></div>
          <Variables label={`事件 ${index + 1} 属性`} pairs={event.attributes} onChange={(attributes) => updateEvent(event.id, { attributes })} />
        </fieldset>)}
        <Button disabled={events.length >= LIMIT.EVENTS} onClick={() => change(() => setEvents((rows) => [...rows, { id: crypto.randomUUID(), offset: rows.at(-1)?.offset ?? '0', type: '', attributes: [] }]))}>添加事件</Button>
      </section>
      {!valid && <StateBanner tone="warn" title="请检查仿真输入" detail="属性名和事件类型必填；属性名不可重复，事件时间须为窗口内的非负整数并按升序排列。" />}
      {mutation.isError && current && <StateBanner tone="error" title="Journey 仿真失败" detail={problemDetail(mutation.error)} />}
      <Button tone="primary" disabled={!valid || mutation.isPending || api.demoMode || !auth.hasPermission('definition:simulate')} onClick={() => mutation.mutate({ revision: revision.current, request: { initialVariables: dictionary(initial), horizonSeconds: horizonValue,
        events: events.map((event) => ({ offsetSeconds: Number(event.offset), eventType: event.type, attributes: dictionary(event.attributes) })) } })}>{mutation.isPending ? '正在仿真…' : '运行仿真'}</Button>
      {result && <section aria-label="Journey 仿真结果">
        <StateBanner tone={result.stopReason === 'COMPLETED' ? 'success' : 'warn'} title={STOP_LABEL[result.stopReason]} detail={`停在节点 ${result.currentNodeId}，已推进 ${result.elapsedSeconds} 秒；未消费输入事件 ${result.remainingEvents} 个。${result.failureCode}`} />
        <RecordTable label="信号推进" columns={['序号', '信号', '事件类型', '发生时间（秒）', '起始节点', '停止节点', '状态']}>{result.steps.map((step) => <tr key={step.sequence}><td>{step.sequence}</td><td>{SIGNAL_LABEL[step.signal]}</td><td>{step.eventType || '—'}</td><td>{step.offsetSeconds}</td><td>{step.fromNodeId}</td><td>{step.toNodeId}</td><td>{step.status}</td></tr>)}</RecordTable>
        <h3>拟生成指令（未执行）</h3>{commands.length ? <RecordTable label="拟生成指令" columns={['信号序号', '发生时间（秒）', '动作', '节点', '模拟指令 ID']}>{commands.map((command) => <tr key={command.commandId}><td>{command.sequence}</td><td>{command.offsetSeconds}</td><td>{COMMAND_LABEL[command.type]}</td><td>{command.nodeId}</td><td>{command.commandId}</td></tr>)}</RecordTable> : <p>本次没有生成动作指令。</p>}
        {result.pendingTimers.length > 0 && <RecordTable label="等待中的计时器" columns={['节点', '预计触发时间（相对秒）']}>{result.pendingTimers.map((timer) => <tr key={timer.nodeId}><td>{timer.nodeId}</td><td>{timer.dueOffsetSeconds}</td></tr>)}</RecordTable>}
      </section>}
    </div>
  </Modal>
}
