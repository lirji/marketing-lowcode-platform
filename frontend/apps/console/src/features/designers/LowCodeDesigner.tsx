import { RecordTable } from '../../components/RecordTable'
import { Select } from '../../components/Select'
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  addEdge, Background, Controls, Handle, MiniMap, Position, ReactFlow,
  useEdgesState, useNodesState, type Connection, type Edge, type NodeProps, type ReactFlowInstance,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { useMutation, useQuery } from '@tanstack/react-query'
import { AlertTriangle, CheckCircle2, ChevronRight, FlaskConical, Redo2, Save, Send, Trash2, Undo2 } from 'lucide-react'
import { Badge, Button, DemoBanner, StateBanner } from '../../components/ui'
import { problemDetail } from '../../shared/api/problem'
import { api, optionalResource } from '../../shared/api/client'
import { fromGraphDefinition, toGraphDefinition, toneForType } from './graph'
import type { DesignerNode } from './designerTypes'
import type { Problem } from '../../shared/model/types'
import { graphDefinitionSchema, type GraphDefinition } from '../../shared/api/schemas'
import { useAuth } from '../../shared/auth/useAuth'
import { nodePorts } from './nodePorts'
import { NodeConfigEditor } from './NodeConfigEditor'
import { NODE_LABELS, supportedNode } from './nodeConfig'
import { problemNodeId } from './problemNodeId'
import { JourneySimulationDialog } from './JourneySimulationDialog'

export type { DesignerNode }
const DESIGNER_STATUS = { SAVED: 'saved', DIRTY: 'dirty', VALIDATED: 'validated', SIMULATED: 'simulated' } as const
// ReactFlow 的固定变更协议值，仅用于区分语义编辑与测量/选择事件。
const FLOW_CHANGE = { DIMENSIONS: 'dimensions', POSITION: 'position', REMOVE: 'remove', ADD: 'add' } as const
type Snapshot = { nodes: DesignerNode[]; edges: Edge[] }

function isGrantNode(data: DesignerNode['data']) {
  const label = data.label ?? ''
  const typeId = data.stableTypeId ?? ''
  return label.includes('发放权益') || label.includes('发召回') || /grant|award|benefit/i.test(typeId)
}

function MarketingNode({ data, selected }: NodeProps<DesignerNode>) {
  const ports = data.ports ?? nodePorts(data.stableTypeId ?? '')
  return (
    <div className={`flow-node ${data.tone} ${selected ? 'selected' : ''}`} tabIndex={0}>
      {ports.inputs.map((port) => <Handle key={port} id={port} type="target" position={Position.Left} title={port} />)}
      <span>{data.label.slice(0, 1)}</span>
      <div>
        <strong>{data.label}</strong>
        <small>{data.subtitle}</small>
        {isGrantNode(data) && <em className="flow-node-badge">尚未接入 AwardIntent</em>}
      </div>
      {ports.outputs.map((port, index) => <Handle key={port} id={port} type="source" position={Position.Right}
        title={port} aria-label={`出口 ${port}`} style={{ top: `${(index + 1) * 100 / (ports.outputs.length + 1)}%` }}>
        {ports.outputs.length > 1 && <span className="flow-port-label">{port}</span>}
      </Handle>)}
    </div>
  )
}
const nodeTypes = { marketing: MarketingNode }

export function LowCodeDesigner({
  title, version, dialect, definitionId, campaignId, nodes: initialNodes, edges: initialEdges, problems,
}: {
  title: string; version: string; dialect: string; definitionId: string; campaignId?: string
  nodes: DesignerNode[]; edges: Edge[]
  problems: Problem[]
}) {
  const auth = useAuth()
  const scope = JSON.stringify([auth.tenantId, auth.subject, [...auth.organizations].sort(), [...auth.shops].sort()])
  const draftKey = `meridian:draft:${scope}:${definitionId}`
  const revision = useRef(0)
  const flow = useRef<ReactFlowInstance<DesignerNode, Edge> | undefined>(undefined)
  const [checkedRevision, setCheckedRevision] = useState<number>()
  const [validated, setValidated] = useState<{ version: number; definitionId: string; revision: number }>()
  const [journeySimulationOpen, setJourneySimulationOpen] = useState(false)
  const closeJourneySimulation = useCallback(() => setJourneySimulationOpen(false), [])
  const [search, setSearch] = useState('')
  const [amountMinor, setAmountMinor] = useState('')
  const [currency, setCurrency] = useState('CNY')
  const [restoring, setRestoring] = useState(Boolean(campaignId) && !api.demoMode)
  const [nodes, setNodes, onNodesChange] = useNodesState<DesignerNode>(initialNodes)
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges)
  const [selectedId, setSelectedId] = useState(initialNodes[0]?.id ?? '')
  const [status, setStatus] = useState<typeof DESIGNER_STATUS[keyof typeof DESIGNER_STATUS]>(DESIGNER_STATUS.DIRTY)
  const [outline, setOutline] = useState(false)
  const [showProblems, setShowProblems] = useState(true)
  const [serverProblems, setServerProblems] = useState<Problem[]>(problems)
  const [savedVersion, setSavedVersion] = useState<number>()
  const [notice, setNotice] = useState<string>()
  const [loadError, setLoadError] = useState<string>()
  const history = useRef<Snapshot[]>([])
  const future = useRef<Snapshot[]>([])
  const originalGraph = useRef<GraphDefinition | undefined>(undefined)
  const selected = nodes.find((node) => node.id === selectedId)
  const canWrite = auth.hasPermission('definition:write')
  const live = !api.demoMode && Boolean(campaignId) && canWrite
  const registry = useQuery({
    queryKey: ['node-registry', dialect],
    queryFn: api.nodeRegistry,
    enabled: !api.demoMode && auth.hasPermission('definition:read'),
  })
  const benefitCatalog = useQuery({
    queryKey: ['benefits', scope], queryFn: api.benefits,
    enabled: !api.demoMode && dialect === 'OFFER_DECISION_DAG' && auth.hasPermission('benefit:read'),
  })
  const paletteItems = (registry.data ?? (api.demoMode ? Object.keys(NODE_LABELS).map((stableTypeId) => ({
    stableTypeId, semanticVersion: '1.0.0', dialects: [stableTypeId.startsWith('offer.') ? 'OFFER_DECISION_DAG' : 'JOURNEY_STATE_MACHINE'],
  })) : []))
    .filter((item) => item.dialects.includes(dialect) && item.semanticVersion === '1.0.0' && supportedNode(item.stableTypeId))
    .map((item) => ({ ...item, label: NODE_LABELS[item.stableTypeId], subtitle: item.stableTypeId, tone: toneForType(item.stableTypeId) }))
  const visiblePalette = paletteItems.filter((item) => `${item.label} ${item.stableTypeId}`.toLowerCase().includes(search.trim().toLowerCase()))

  useEffect(() => {
    let active = true
    const startedAt = revision.current
    originalGraph.current = undefined
    const restore = async () => {
      if (!api.demoMode && campaignId) {
        try {
          const bundle = await optionalResource(() => api.latestDefinition(campaignId, dialect))
          if (active && revision.current === startedAt && bundle?.graph) {
            originalGraph.current = bundle.graph
            const snapshot = fromGraphDefinition(bundle.graph)
            if (snapshot.nodes.length > 0) {
              setNodes(snapshot.nodes)
              setEdges(snapshot.edges)
              setSavedVersion(bundle.version)
              setStatus(DESIGNER_STATUS.SAVED)
              setLoadError(undefined)
              setRestoring(false)
              return
            }
          }
          if (active) setRestoring(false)
          return
        } catch (cause) {
          if (active) { setLoadError(problemDetail(cause, '未能从控制面加载定义，请刷新重试；为避免覆盖已有版本，暂不允许保存')); setRestoring(false) }
          return
        }
      }
      try {
        const raw = localStorage.getItem(draftKey)
        if (!raw) return
        const parsed = JSON.parse(raw) as Snapshot & { graph?: unknown }
        if (active && revision.current === startedAt && Array.isArray(parsed.nodes) && Array.isArray(parsed.edges)) {
          const storedGraph = graphDefinitionSchema.safeParse(parsed.graph)
          if (storedGraph.success && storedGraph.data.dialect === dialect) originalGraph.current = storedGraph.data
          setNodes(parsed.nodes)
          setEdges(parsed.edges)
          setStatus(DESIGNER_STATUS.DIRTY)
        }
      } catch { /* ignore corrupt drafts */ }
    }
    void restore()
    return () => { active = false }
  }, [campaignId, dialect, draftKey, setEdges, setNodes])

  useEffect(() => {
    if (status !== DESIGNER_STATUS.DIRTY) return
    const onLeave = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', onLeave)
    return () => window.removeEventListener('beforeunload', onLeave)
  }, [status])

  const graph = () => toGraphDefinition({ definitionId, dialect, nodes, edges, title, original: originalGraph.current })
  const capture = () => ({ graph: graph(), revision: revision.current, nodes, edges })
  type Draft = ReturnType<typeof capture>
  const persistDefinition = async (draft: Draft) => {
    localStorage.setItem(draftKey, JSON.stringify(draft))
    if (!live || !campaignId) return { version: undefined }
    return api.saveDefinition({ campaignId, graph: draft.graph })
  }
  const saveMutation = useMutation({
    mutationFn: persistDefinition,
    onSuccess: (value, draft) => {
      if (draft.revision !== revision.current) return
      setSavedVersion(value.version); setValidated(undefined); setStatus(DESIGNER_STATUS.SAVED)
      setNotice(live ? `已写入版本 v${value.version}，校验后可提交审核` : '已保存到当前身份的浏览器草稿')
    },
  })
  const validateMutation = useMutation({
    mutationFn: async (draft: Draft) => {
      const saved = await persistDefinition(draft)
      if (!live || saved.version == null) throw new Error('当前草稿未绑定可写活动')
      if (draft.revision === revision.current) {
        setSavedVersion(saved.version); setStatus(DESIGNER_STATUS.SAVED); setValidated(undefined); setCheckedRevision(undefined)
      }
      try {
        return { result: await api.validate(draft.graph.definitionId, saved.version), version: saved.version }
      } catch (cause) {
        throw new Error(`v${saved.version} 已保存，但校验失败：${problemDetail(cause)}。可重试校验。`)
      }
    },
    onSuccess: ({ result, version }, draft) => {
      if (draft.revision !== revision.current) return
      setSavedVersion(version)
      setCheckedRevision(draft.revision)
      setServerProblems(result.issues.map((issue) => ({ ...issue, severity: issue.severity === 'ERROR' ? 'ERROR' : 'WARNING' })))
      setShowProblems(true)
      setValidated(result.valid ? { definitionId: draft.graph.definitionId, version, revision: draft.revision } : undefined)
      setStatus(result.valid ? DESIGNER_STATUS.VALIDATED : DESIGNER_STATUS.SAVED)
      setNotice(result.valid ? `v${version} 校验通过，可提交审核` : `v${version} 已保存，但校验未通过`)
    },
  })
  const simulateMutation = useMutation({
    mutationFn: async (input: { target: NonNullable<typeof validated>; amountMinor: string; currency: string }) => {
      return api.simulate(input.target.definitionId, input.target.version, { amountMinor: input.amountMinor, currency: input.currency })
    },
    onSuccess: (value, input) => {
      if (input.target.revision !== revision.current) return
      if ('discountMinor' in value) {
        setStatus(DESIGNER_STATUS.SIMULATED)
        setNotice(`金额试算（${input.currency}）：原金额 ${input.amountMinor} 分，优惠 ${value.discountMinor} 分，应付 ${value.payableMinor} 分；未核验生效时间、库存与领取资格`)
      }
    },
  })
  const submitMutation = useMutation({
    mutationFn: (target: NonNullable<typeof validated>) => api.submit(target.definitionId, target.version),
    onSuccess: (value, target) => {
      if (target.revision === revision.current) setNotice(`v${target.version} 已提交审核 ${value.caseId}`)
    },
  })
  const startAction = (action: () => void) => {
    saveMutation.reset(); validateMutation.reset(); simulateMutation.reset(); submitMutation.reset()
    setNotice(undefined)
    action()
  }
  const busy = saveMutation.isPending || validateMutation.isPending || simulateMutation.isPending || submitMutation.isPending
  const editable = canWrite && !restoring && !loadError
  const ready = live && validated?.revision === revision.current && !busy && !loadError && !restoring
  const validAmount = /^\d+$/.test(amountMinor) && Number.isSafeInteger(Number(amountMinor))
  const markDirty = () => { revision.current++; setJourneySimulationOpen(false); setValidated(undefined); setStatus(DESIGNER_STATUS.DIRTY); setServerProblems([]); setCheckedRevision(undefined); setNotice(undefined) }
  const remember = () => { history.current.push({ nodes, edges }); if (history.current.length > 30) history.current.shift(); future.current = [] }
  const undo = () => { const snapshot = history.current.pop(); if (!snapshot) return; future.current.push({ nodes, edges }); setNodes(snapshot.nodes); setEdges(snapshot.edges); markDirty() }
  const redo = () => { const snapshot = future.current.pop(); if (!snapshot) return; history.current.push({ nodes, edges }); setNodes(snapshot.nodes); setEdges(snapshot.edges); markDirty() }
  const connect = (connection: Connection) => { if (!editable || connection.source === connection.target) return; remember(); setEdges((current) => addEdge({ ...connection, animated: true }, current)); markDirty() }
  const addNode = (item: (typeof paletteItems)[number]) => { remember(); const id = `${dialect.toLowerCase()}-${crypto.randomUUID()}`; setNodes((current) => [...current, { id, type: 'marketing', position: { x: 40 + (current.length % 4) * 230, y: 80 + Math.floor(current.length / 4) * 140 }, data: { label: item.label, subtitle: item.subtitle, tone: item.tone, config: {}, stableTypeId: item.stableTypeId, semanticVersion: item.semanticVersion } }]); setSelectedId(id); markDirty() }
  const remove = () => { if (!selectedId) return; remember(); setNodes((current) => current.filter((node) => node.id !== selectedId)); setEdges((current) => current.filter((edge) => edge.source !== selectedId && edge.target !== selectedId)); setSelectedId(''); markDirty() }
  const updateConfig = (name: string, value: string) => {
    remember()
    setNodes((current) => current.map((node) => {
      if (node.id !== selectedId) return node
      const config = { ...node.data.config }
      if (value === '') delete config[name]; else config[name] = value
      // 比例旧别名与新字段必须保持一致，不能在编辑后保留相互冲突的两个值。
      if (name === 'discountBasisPoints' && 'basisPoints' in config) { if (value) config.basisPoints = value; else delete config.basisPoints }
      return { ...node, data: { ...node.data, config } }
    }))
    markDirty()
  }
  const routeTo = (port: string, target: string) => {
    remember()
    setEdges((current) => [...current.filter((edge) => !(edge.source === selectedId && (edge.sourceHandle ?? 'next') === port)),
      ...(target ? [{ id: `${selectedId}-${port}-${target}`, source: selectedId, sourceHandle: port, target, targetHandle: 'in', animated: true }] : [])])
    markDirty()
  }
  const displayedProblems = serverProblems
  const actionError = (saveMutation.variables?.revision === revision.current ? saveMutation.error : undefined)
    ?? (validateMutation.variables?.revision === revision.current ? validateMutation.error : undefined)
    ?? (simulateMutation.variables?.target.revision === revision.current ? simulateMutation.error : undefined)
    ?? (submitMutation.variables?.revision === revision.current ? submitMutation.error : undefined)
  const weights = nodes.map((node) => registry.data?.find((item) => item.stableTypeId === node.data.stableTypeId && item.semanticVersion === (node.data.semanticVersion ?? '1.0.0'))?.costWeight)
  const complexity = weights.every((weight) => weight != null) ? weights.reduce<number>((sum, weight) => sum + (weight ?? 0), 0) : undefined
  const portsFor = (node: DesignerNode) => {
    const spec = registry.data?.find((item) => item.stableTypeId === node.data.stableTypeId && item.semanticVersion === (node.data.semanticVersion ?? '1.0.0'))
    return { inputs: spec?.inputPorts ? Object.keys(spec.inputPorts) : nodePorts(node.data.stableTypeId ?? '').inputs,
      outputs: spec?.outputPorts ? Object.keys(spec.outputPorts) : nodePorts(node.data.stableTypeId ?? '').outputs }
  }

  return <div className="designer-page">
    <header className="designer-header"><div><p className="eyebrow">{dialect}{campaignId ? ` · ${campaignId}` : ''}</p><h1>{title}</h1><span>{savedVersion ? `v${savedVersion}` : version} · <i className={`save-state ${status}`}>{status === DESIGNER_STATUS.SAVED ? '已保存' : status === DESIGNER_STATUS.DIRTY ? '有未保存更改' : status === DESIGNER_STATUS.VALIDATED ? '校验通过' : '仿真完成'}</i></span></div><div className="designer-actions"><Button onClick={() => startAction(() => saveMutation.mutate(capture()))} disabled={!editable || busy}><Save size={15} />保存</Button><Button onClick={() => startAction(() => validateMutation.mutate(capture()))} disabled={!editable || !live || busy}><CheckCircle2 size={15} />校验</Button>{dialect === 'OFFER_DECISION_DAG' && <Button onClick={() => { if (validated) startAction(() => simulateMutation.mutate({ target: validated, amountMinor, currency })) }} disabled={!ready || !validAmount || !/^[A-Z]{3}$/.test(currency)}><FlaskConical size={15} />金额试算</Button>}{dialect === 'JOURNEY_STATE_MACHINE' && <Button disabled={!ready || api.demoMode || !auth.hasPermission('definition:simulate')} onClick={() => setJourneySimulationOpen(true)}><FlaskConical size={15} />旅程仿真</Button>}<Button tone="primary" onClick={() => { if (validated) startAction(() => submitMutation.mutate(validated)) }} disabled={!ready || !auth.hasPermission('definition:submit')}><Send size={15} />提交审核</Button></div></header>
    <DemoBanner />
    {restoring && <div className="designer-notice"><StateBanner tone="info" title="正在加载已保存定义" detail="加载完成后可编辑，避免覆盖已有版本。" /></div>}
    <div className="designer-simulation-inputs">{dialect === 'OFFER_DECISION_DAG' ? <><label className="field"><span>试算订单金额（分）</span><input aria-label="试算订单金额（分）" inputMode="numeric" value={amountMinor} onChange={(event) => setAmountMinor(event.target.value)} /></label><label className="field"><span>试算币种</span><input aria-label="试算币种" value={currency} onChange={(event) => setCurrency(event.target.value.toUpperCase())} maxLength={3} /></label><p>保存并校验当前内容后可试算和提交；编辑会使原校验失效。金额试算不支持商品、人群、店铺范围或运费优惠。</p></> : <p>保存并校验当前内容后可仿真或提交审核。仿真仅展示输入下的状态和拟生成指令；实际触达与履约仍需运行环境验收。</p>}</div>
    {!campaignId && !api.demoMode && <div className="designer-notice"><StateBanner tone="info" title="未绑定活动" detail="侧栏打开的是空白草稿，保存不会写入控制面。请从「计划与活动」创建或打开活动后再设计。" /></div>}
    {(actionError || loadError) && <div className="designer-notice"><StateBanner tone="error" title="设计器操作失败" detail={loadError ?? problemDetail(actionError)} /></div>}
    {notice && <div className="designer-notice"><StateBanner tone="success" title="操作已受理" detail={notice} /></div>}
    <div className="designer-grid">
      <aside className="node-palette"><div className="designer-section-title"><span>节点与模板</span><small>{visiblePalette.length} 个匹配</small></div><label className="palette-search"><span className="sr-only">搜索节点</span><input placeholder="搜索名称或节点类型…" value={search} onChange={(event) => setSearch(event.target.value)} /></label>{registry.isError && <p role="alert">节点目录加载失败，请刷新重试。</p>}{visiblePalette.map((item) => <button key={`${item.stableTypeId}@${item.semanticVersion}`} disabled={!editable} onClick={() => addNode(item)}><i className={item.tone}>{item.label.slice(0, 1)}</i><div><strong>{item.label}</strong><small>{item.subtitle}</small></div><ChevronRight size={13} /></button>)}{!visiblePalette.length && <p className="muted">{registry.isLoading ? '正在加载节点…' : '没有匹配节点'}</p>}<div className="complexity-meter"><div><span>注册节点成本合计</span><b>{complexity ?? '未知'}</b></div><p className="field-hint">按注册表权重计算，是否可发布以控制面校验为准。</p></div></aside>
      <section className="canvas-stage" aria-label={`${title}画布`}>
        <div className="canvas-toolbar"><button onClick={undo} disabled={!editable || !history.current.length} aria-label="撤销"><Undo2 size={15} /></button><button onClick={redo} disabled={!editable || !future.current.length} aria-label="重做"><Redo2 size={15} /></button><button onClick={remove} disabled={!editable || !selectedId} aria-label="删除节点"><Trash2 size={15} /></button><span /><button className={outline ? 'active' : ''} onClick={() => setOutline(!outline)}>线性大纲</button></div>
        {outline ? <RecordTable label="节点大纲" columns={['节点 ID', '节点名称', '说明', '操作']} actions>{nodes.map((node) => <tr key={node.id}><td>{node.id}</td><td>{node.data.label}</td><td>{node.data.subtitle}</td><td><Button aria-label={`定位节点：${node.id}`} onClick={() => { setSelectedId(node.id); setOutline(false) }}>定位节点</Button></td></tr>)}</RecordTable> : <ReactFlow<DesignerNode, Edge> nodes={nodes.map((node) => ({ ...node, data: { ...node.data, ports: portsFor(node) } }))} edges={edges} nodeTypes={nodeTypes} nodesDraggable={editable} nodesConnectable={editable} edgesReconnectable={editable} onNodesChange={(changes) => { onNodesChange(changes); if (changes.some((change) => change.type === FLOW_CHANGE.DIMENSIONS)) void flow.current?.fitView({ maxZoom: 1, padding: 0.2 }); if (editable && changes.some((change) => change.type === FLOW_CHANGE.POSITION && change.dragging)) markDirty() }} onEdgesChange={(changes) => { onEdgesChange(changes); if (changes.some((change) => change.type === FLOW_CHANGE.REMOVE || change.type === FLOW_CHANGE.ADD)) markDirty() }} onConnect={connect} onInit={(instance) => { flow.current = instance }} onNodeClick={(_, node) => setSelectedId(node.id)} fitView fitViewOptions={{ maxZoom: 1, padding: 0.2 }} minZoom={0.2} maxZoom={1.8} deleteKeyCode={null}><Background color="#cad7d5" gap={22} size={1} />{nodes.length > 6 && <MiniMap pannable zoomable nodeColor={(node) => ({ teal: '#188e81', blue: '#377fbd', amber: '#d18a27', violet: '#7659b1', slate: '#647681' }[String(node.data?.tone)] ?? '#647681')} />}<Controls /></ReactFlow>}
      </section>
      <aside className="inspector"><div className="designer-section-title"><span>属性与治理</span><Badge tone="info">节点配置</Badge></div>{selected ? <><div className="selected-node"><i className={selected.data.tone}>{selected.data.label.slice(0,1)}</i><div><strong>{selected.data.label}</strong><small>{selected.id}</small></div></div><NodeConfigEditor key={selected.id} node={selected} onChange={updateConfig} disabled={!editable} benefits={benefitCatalog.data} benefitError={benefitCatalog.error ? problemDetail(benefitCatalog.error) : undefined} /><div className="node-routes"><strong>出口连接</strong>{portsFor(selected).outputs.length === 0 && <p className="field-hint">结束节点没有出口。</p>}{portsFor(selected).outputs.map((port) => <label className="field" key={port}><span>出口 {port}</span><Select aria-label={`出口 ${port}`} disabled={!editable} value={edges.find((edge) => edge.source === selectedId && (edge.sourceHandle ?? 'next') === port)?.target ?? ''} onChange={(event) => routeTo(port, event.target.value)}><option value="">未连接</option>{nodes.filter((node) => node.id !== selectedId && portsFor(node).inputs.includes('in')).map((node) => <option key={node.id} value={node.id}>{node.data.label} · {node.id}</option>)}</Select></label>)}</div></> : <div className="empty-inspector">选择节点查看属性</div>}</aside>
    </div>
    {journeySimulationOpen && validated && validated.revision === revision.current && <JourneySimulationDialog key={JSON.stringify([scope, auth.permissions, validated])} definitionId={validated.definitionId} version={validated.version} onClose={closeJourneySimulation} />}
    <section className={`problems-drawer ${showProblems ? 'open' : ''}`}><button className="problems-title" type="button" onClick={() => setShowProblems(!showProblems)}><AlertTriangle size={15} /><strong>Problems {displayedProblems.length}</strong><span>{checkedRevision === revision.current ? '来自控制面校验' : '尚未校验当前内容'}</span></button>{showProblems && <div className="problem-list">{displayedProblems.length === 0 ? <p className="muted">{checkedRevision === revision.current ? '没有校验问题。' : '请校验当前内容，尚无校验结果。'}</p> : <RecordTable label="校验问题" columns={['严重程度', '问题码', '问题说明', '字段路径', '操作']} actions>{displayedProblems.map((problem, index) => <tr key={`${problem.code}-${problem.pointer}-${index}`}><td><Badge tone={problem.severity === 'ERROR' ? 'danger' : 'warn'}>{problem.severity}</Badge></td><td>{problem.code}</td><td>{problem.message}</td><td>{problem.pointer}</td><td><Button disabled={!problemNodeId(problem, nodes)} onClick={() => { setSelectedId(problemNodeId(problem, nodes) ?? ''); setOutline(false) }}>查看节点</Button></td></tr>)}</RecordTable>}</div>}</section>
  </div>
}
