import { useEffect, useRef, useState } from 'react'
import {
  addEdge, Background, Controls, Handle, MiniMap, Position, ReactFlow,
  useEdgesState, useNodesState, type Connection, type Edge, type NodeProps,
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
import { useAuth } from '../../shared/auth/useAuth'

export type { DesignerNode }
type Snapshot = { nodes: DesignerNode[]; edges: Edge[] }

function isGrantNode(data: DesignerNode['data']) {
  const label = data.label ?? ''
  const typeId = data.stableTypeId ?? ''
  return label.includes('发放权益') || label.includes('发召回') || /grant|award|benefit/i.test(typeId)
}

function MarketingNode({ data, selected }: NodeProps<DesignerNode>) {
  return (
    <div className={`flow-node ${data.tone} ${selected ? 'selected' : ''}`} tabIndex={0}>
      <Handle type="target" position={Position.Left} />
      <span>{data.label.slice(0, 1)}</span>
      <div>
        <strong>{data.label}</strong>
        <small>{data.subtitle}</small>
        {isGrantNode(data) && <em className="flow-node-badge">尚未接入 AwardIntent</em>}
      </div>
      <Handle type="source" position={Position.Right} />
    </div>
  )
}
const nodeTypes = { marketing: MarketingNode }

export function LowCodeDesigner({
  title, version, dialect, definitionId, campaignId, nodes: initialNodes, edges: initialEdges, palette, problems,
}: {
  title: string; version: string; dialect: string; definitionId: string; campaignId?: string
  nodes: DesignerNode[]; edges: Edge[]
  palette: { label: string; subtitle: string; tone: DesignerNode['data']['tone'] }[]; problems: Problem[]
}) {
  const auth = useAuth()
  const draftKey = `meridian:draft:${definitionId}`
  const [nodes, setNodes, onNodesChange] = useNodesState<DesignerNode>(initialNodes)
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges)
  const [selectedId, setSelectedId] = useState(initialNodes[0]?.id ?? '')
  const [status, setStatus] = useState<'saved'|'dirty'|'validated'|'simulated'>('saved')
  const [outline, setOutline] = useState(false)
  const [showProblems, setShowProblems] = useState(true)
  const [serverProblems, setServerProblems] = useState<Problem[]>(problems)
  const [savedVersion, setSavedVersion] = useState<number>()
  const [notice, setNotice] = useState<string>()
  const [loadError, setLoadError] = useState<string>()
  const history = useRef<Snapshot[]>([])
  const future = useRef<Snapshot[]>([])
  const selected = nodes.find((node) => node.id === selectedId)
  const canWrite = auth.hasPermission('definition:write')
  const live = !api.demoMode && Boolean(campaignId) && canWrite
  const registry = useQuery({
    queryKey: ['node-registry', dialect],
    queryFn: api.nodeRegistry,
    enabled: !api.demoMode && auth.hasPermission('definition:read'),
  })
  const livePalette = (registry.data ?? [])
    .filter((item) => item.dialects.includes(dialect) && item.stableTypeId !== 'offer.end')
    .map((item) => ({
      label: item.stableTypeId.split('.').pop() ?? item.stableTypeId,
      subtitle: `${item.stableTypeId}@${item.semanticVersion}`,
      tone: toneForType(item.stableTypeId),
      stableTypeId: item.stableTypeId,
    }))
  const paletteItems = livePalette.length > 0 ? livePalette : palette.map((item) => ({ ...item, stableTypeId: undefined as string | undefined }))

  useEffect(() => {
    let active = true
    const restore = async () => {
      if (!api.demoMode && campaignId) {
        try {
          const bundle = await optionalResource(() => api.latestDefinition(campaignId, dialect))
          if (active && bundle?.graph) {
            const snapshot = fromGraphDefinition(bundle.graph)
            if (snapshot.nodes.length > 0) {
              setNodes(snapshot.nodes)
              setEdges(snapshot.edges)
              setSavedVersion(bundle.version)
              setStatus('saved')
              setLoadError(undefined)
              return
            }
          }
          // 新活动还没有定义：用空白起步图，不要回落到双11本地草稿。
          return
        } catch (cause) {
          if (active) setLoadError(problemDetail(cause, '未能从控制面加载定义，已回退到本地草稿'))
        }
      }
      try {
        const raw = localStorage.getItem(draftKey)
        if (!raw) return
        const parsed = JSON.parse(raw) as Snapshot
        if (Array.isArray(parsed.nodes) && Array.isArray(parsed.edges)) {
          setNodes(parsed.nodes)
          setEdges(parsed.edges)
          setStatus('dirty')
        }
      } catch { /* ignore corrupt drafts */ }
    }
    void restore()
    return () => { active = false }
  }, [campaignId, dialect, draftKey, setEdges, setNodes])

  useEffect(() => {
    if (status !== 'dirty') return
    const onLeave = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', onLeave)
    return () => window.removeEventListener('beforeunload', onLeave)
  }, [status])

  const graph = () => toGraphDefinition({ definitionId, dialect, nodes, edges, title })
  const persistDraft = () => localStorage.setItem(draftKey, JSON.stringify({ nodes, edges }))
  const persistDefinition = async () => {
    persistDraft()
    if (!live || !campaignId) return { version: savedVersion }
    return api.saveDefinition({ campaignId, graph: graph() })
  }

  const saveMutation = useMutation({
    mutationFn: persistDefinition,
    onSuccess: (value) => { setSavedVersion(value.version); setStatus('saved'); setNotice(live ? `已写入版本 v${value.version}` : '仅保存到浏览器草稿（演示模式或缺少活动 ID）') },
  })
  const validateMutation = useMutation({
    mutationFn: async () => {
      const saved = await persistDefinition()
      setSavedVersion(saved.version)
      if (!live || saved.version == null) throw new Error('演示模式不会调用校验服务')
      return api.validate(definitionId, saved.version)
    },
    onSuccess: (value) => {
      setServerProblems(value.issues.map((issue) => ({ code: issue.code, message: issue.message, pointer: issue.pointer, severity: issue.severity === 'ERROR' ? 'ERROR' : 'WARNING' })))
      setShowProblems(true)
      setStatus(value.valid ? 'validated' : 'dirty')
      setNotice(value.valid ? '校验通过' : '校验发现错误，未进入可提交状态')
    },
  })
  const simulateMutation = useMutation({
    mutationFn: async () => {
      if (savedVersion == null) throw new Error('请先保存并校验后再仿真')
      if (!live) throw new Error('演示模式不会调用仿真服务')
      return api.simulate(definitionId, savedVersion, { amountMinor: '68800', 'member.level': 'PLUS' })
    },
    onSuccess: (value) => {
      setStatus('simulated')
      if ('discountMinor' in value) {
        setNotice(`仿真完成：优惠 ${value.discountMinor} 分，应付 ${value.payableMinor} 分`)
        return
      }
      setNotice('仿真完成（裂变结果请在邀请有礼设计器查看）')
    },
  })
  const submitMutation = useMutation({
    mutationFn: async () => {
      if (savedVersion == null) throw new Error('请先保存并校验')
      if (!live) throw new Error('演示模式不会提交审核')
      if (!auth.hasPermission('definition:submit')) throw new Error('缺少 definition:submit 权限')
      return api.submit(definitionId, savedVersion)
    },
    onSuccess: (value) => { setNotice(`已提交审核 ${value.caseId}`) },
  })

  const remember = () => { history.current.push({ nodes, edges }); if (history.current.length > 30) history.current.shift(); future.current = [] }
  const undo = () => { const snapshot = history.current.pop(); if (!snapshot) return; future.current.push({ nodes, edges }); setNodes(snapshot.nodes); setEdges(snapshot.edges); setStatus('dirty') }
  const redo = () => { const snapshot = future.current.pop(); if (!snapshot) return; history.current.push({ nodes, edges }); setNodes(snapshot.nodes); setEdges(snapshot.edges); setStatus('dirty') }
  const connect = (connection: Connection) => { if (connection.source === connection.target) return; remember(); setEdges((current) => addEdge({ ...connection, animated: true, style: { stroke: '#63827e' } }, current)); setStatus('dirty') }
  const addNode = (item: (typeof paletteItems)[number]) => { remember(); const id = `${dialect.toLowerCase()}-${Date.now()}`; setNodes((current) => [...current, { id, type: 'marketing', position: { x: 130 + current.length * 35, y: 90 + (current.length % 4) * 85 }, data: { label: item.label, subtitle: item.subtitle, tone: item.tone, config: {}, stableTypeId: item.stableTypeId } }]); setSelectedId(id); setStatus('dirty') }
  const remove = () => { if (!selectedId) return; remember(); setNodes((current) => current.filter((node) => node.id !== selectedId)); setEdges((current) => current.filter((edge) => edge.source !== selectedId && edge.target !== selectedId)); setSelectedId(''); setStatus('dirty') }
  const updateConfig = (name: string, value: string) => { setNodes((current) => current.map((node) => node.id === selectedId ? { ...node, data: { ...node.data, config: { ...node.data.config, [name]: value } } } : node)); setStatus('dirty') }
  const displayedProblems = serverProblems.length > 0 ? serverProblems : problems
  const actionError = saveMutation.error ?? validateMutation.error ?? simulateMutation.error ?? submitMutation.error
  const complexity = Math.min(100, nodes.length * 8)

  return <div className="designer-page">
    <header className="designer-header"><div><p className="eyebrow">{dialect}{campaignId ? ` · ${campaignId}` : ''}</p><h1>{title}</h1><span>{savedVersion ? `v${savedVersion}` : version} · <i className={`save-state ${status}`}>{status === 'saved' ? '已保存' : status === 'dirty' ? '有未保存更改' : status === 'validated' ? '校验通过' : '仿真完成'}</i></span></div><div className="designer-actions"><Button onClick={() => saveMutation.mutate()} disabled={saveMutation.isPending}><Save size={15} />保存</Button><Button onClick={() => validateMutation.mutate()} disabled={!live || validateMutation.isPending} title={live ? '调用控制面校验' : '演示模式不可执行'}><CheckCircle2 size={15} />校验</Button><Button onClick={() => simulateMutation.mutate()} disabled={!live || simulateMutation.isPending} title={live ? '调用控制面仿真' : '演示模式不可执行'}><FlaskConical size={15} />仿真</Button><Button tone="primary" onClick={() => submitMutation.mutate()} disabled={!live || !auth.hasPermission('definition:submit') || submitMutation.isPending} title={live ? '提交审核' : '演示模式不可执行'}><Send size={15} />提交审核</Button></div></header>
    <DemoBanner />
    {!campaignId && !api.demoMode && <div className="designer-notice"><StateBanner tone="info" title="未绑定活动" detail="侧栏打开的是空白草稿，保存不会写入控制面。请从「计划与活动」创建或打开活动后再设计。" /></div>}
    {(actionError || loadError) && <div className="designer-notice"><StateBanner tone="error" title="设计器操作失败" detail={loadError ?? problemDetail(actionError)} /></div>}
    {notice && <div className="designer-notice"><StateBanner tone="success" title="操作已受理" detail={notice} /></div>}
    <div className="designer-grid">
      <aside className="node-palette"><div className="designer-section-title"><span>节点与模板</span><small>{paletteItems.length} 个可用</small></div><label className="palette-search"><span className="sr-only">搜索节点</span><input placeholder="搜索节点…" /></label>{paletteItems.map((item) => <button key={item.stableTypeId ?? item.label} onClick={() => addNode(item)}><i className={item.tone}>{item.label.slice(0, 1)}</i><div><strong>{item.label}</strong><small>{item.subtitle}</small></div><ChevronRight size={13} /></button>)}<div className="complexity-meter"><div><span>复杂度预算</span><b>{complexity} / 100</b></div><progress value={complexity} max="100" /></div></aside>
      <section className="canvas-stage" aria-label={`${title}画布`}>
        <div className="canvas-toolbar"><button onClick={undo} disabled={!history.current.length} aria-label="撤销"><Undo2 size={15} /></button><button onClick={redo} disabled={!future.current.length} aria-label="重做"><Redo2 size={15} /></button><button onClick={remove} disabled={!selectedId} aria-label="删除节点"><Trash2 size={15} /></button><span /><button className={outline ? 'active' : ''} onClick={() => setOutline(!outline)}>线性大纲</button></div>
        {outline ? <ol className="graph-outline">{nodes.map((node) => <li key={node.id}><button onClick={() => { setSelectedId(node.id); setOutline(false) }}><span>{node.data.label}</span><small>{node.data.subtitle}</small></button></li>)}</ol> : <ReactFlow nodes={nodes} edges={edges} nodeTypes={nodeTypes} onNodesChange={onNodesChange} onEdgesChange={onEdgesChange} onConnect={connect} onNodeClick={(_, node) => setSelectedId(node.id)} fitView minZoom={0.35} maxZoom={1.8} deleteKeyCode={null}><Background color="#cad7d5" gap={22} size={1} /><MiniMap pannable zoomable nodeColor={(node) => ({ teal: '#188e81', blue: '#377fbd', amber: '#d18a27', violet: '#7659b1', slate: '#647681' }[String(node.data?.tone)] ?? '#647681')} /><Controls /></ReactFlow>}
      </section>
      <aside className="inspector"><div className="designer-section-title"><span>属性与治理</span><Badge tone="info">Schema 驱动</Badge></div>{selected ? isGrantNode(selected.data) ? <><div className="selected-node"><i className={selected.data.tone}>{selected.data.label.slice(0,1)}</i><div><strong>{selected.data.label}</strong><small>{selected.id}</small></div></div><Badge tone="warn">尚未接入 AwardIntent</Badge><p className="grant-intent-note">Journey「发放权益」还不会写出 AwardIntent。这里不能重选金额或 SKU，也不要当成已经发奖。</p></> : <><div className="selected-node"><i className={selected.data.tone}>{selected.data.label.slice(0,1)}</i><div><strong>{selected.data.label}</strong><small>{selected.id}</small></div></div><label className="field"><span>业务字段</span><select value={selected.data.config.field ?? 'member.level'} onChange={(event) => updateConfig('field', event.target.value)}><option>member.level</option><option>product.category</option><option>cart.amount</option><option>audience.snapshot</option></select><small>来源：会员域 · 新鲜度 ≤ 5m</small></label><label className="field"><span>匹配值</span><input value={selected.data.config.value ?? 'PLUS'} onChange={(event) => updateConfig('value', event.target.value)} /></label><label className="field"><span>缺失值策略</span><select value={selected.data.config.missing ?? 'REJECT'} onChange={(event) => updateConfig('missing', event.target.value)}><option value="REJECT">拒绝候选</option><option value="NO_MATCH">按未命中</option><option value="GENERIC">通用路径</option></select></label><div className="governance-note"><ShieldIcon /><div><strong>运行时约束</strong><p>纯函数、禁止 I/O；P99 deadline 20ms；失败时 fail-closed。</p></div></div></> : <div className="empty-inspector">选择节点查看属性</div>}</aside>
    </div>
    <section className={`problems-drawer ${showProblems ? 'open' : ''}`}><button className="problems-title" type="button" onClick={() => setShowProblems(!showProblems)}><AlertTriangle size={15} /><strong>Problems {displayedProblems.length}</strong><span>{live ? '来自控制面校验' : '尚未校验'}</span></button>{showProblems && <div className="problem-list">{displayedProblems.length === 0 ? <p className="muted">没有校验问题。</p> : displayedProblems.map((problem) => <button key={problem.code} type="button" onClick={() => setSelectedId(nodes[Math.min(nodes.length - 1, 1)]?.id ?? '')}><Badge tone={problem.severity === 'ERROR' ? 'danger' : 'warn'}>{problem.severity}</Badge><strong>{problem.message}</strong><code>{problem.pointer}</code></button>)}</div>}</section>
  </div>
}

function ShieldIcon() { return <span aria-hidden="true">✓</span> }
