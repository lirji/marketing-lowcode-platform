import { describe, expect, it } from 'vitest'
import { fromGraphDefinition, toGraphDefinition, toneForType } from './graph'
import type { DesignerNode } from './designerTypes'
import type { GraphDefinition } from '../../shared/api/schemas'

const nodes: DesignerNode[] = [
  { id: 'start', type: 'marketing', position: { x: 0, y: 0 }, data: { label: '开始', subtitle: 'start', tone: 'slate', config: {}, stableTypeId: 'offer.start' } },
  { id: 'cond', type: 'marketing', position: { x: 100, y: 0 }, data: { label: '条件', subtitle: 'if', tone: 'violet', config: { field: 'member.level' }, stableTypeId: 'offer.condition' } },
]
const edges = [{ id: 'e1', source: 'start', target: 'cond' }]

describe('graph mapping', () => {
  it('round-trips designer nodes through GraphDefinition', () => {
    const graph = toGraphDefinition({ definitionId: 'offer-1', dialect: 'OFFER_DECISION_DAG', nodes, edges, title: '主会场' })
    expect(graph.nodes.map((node) => node.stableTypeId)).toContain('offer.start')
    expect(graph.nodes.some((node) => node.stableTypeId === 'offer.end')).toBe(true)
    const restored = fromGraphDefinition(graph)
    expect(restored.nodes.map((node) => node.id)).toEqual(['start', 'cond', 'offer-1-end'])
    expect(restored.edges[0]?.source).toBe('start')
  })

  it('preserves a saved definition including terminal IDs, versions and runtime metadata', () => {
    const original: GraphDefinition = {
      definitionId: 'saved-offer', dialect: 'OFFER_DECISION_DAG', dialectVersion: '1.2.0',
      nodes: [
        { id: 'start', stableTypeId: 'offer.start', semanticVersion: '1.1.0', config: {} },
        { id: 'terminal-original', stableTypeId: 'offer.end', semanticVersion: '1.0.0', config: {} },
      ],
      edges: [{ id: 'done', sourceNodeId: 'start', sourcePort: 'next', targetNodeId: 'terminal-original', targetPort: 'in' }],
      variables: { stateTtlSeconds: '3600', maxStepsPerSignal: '20' },
      annotations: { title: '原始标题', terms: '已审核条款', owner: 'team' },
    }
    const restored = fromGraphDefinition(original)
    const saved = toGraphDefinition({ definitionId: 'page-default', dialect: original.dialect, title: '页面标题', original, ...restored })
    expect(saved).toEqual(original)
    const ids = new Set(saved.nodes.map((node) => node.id))
    expect(saved.edges.every((edge) => ids.has(edge.sourceNodeId) && ids.has(edge.targetNodeId))).toBe(true)
  })

  it('merges a node edit without losing journey variables or undoing explicit deletions', () => {
    const original: GraphDefinition = {
      definitionId: 'journey', dialect: 'JOURNEY_STATE_MACHINE', dialectVersion: '1.0.0',
      nodes: [{ id: 'trigger', stableTypeId: 'journey.trigger', semanticVersion: '1.0.0', config: { event: 'OLD' } }],
      edges: [], variables: { stateTtlSeconds: '3600' }, annotations: { source: 'import' },
    }
    const restored = fromGraphDefinition(original)
    restored.nodes[0].data.config = { event: 'NEW' }
    const saved = toGraphDefinition({ definitionId: original.definitionId, dialect: original.dialect, title: '', original, ...restored })
    expect(saved.nodes[0].config).toEqual({ event: 'NEW' })
    expect(saved.variables).toEqual(original.variables)
    expect(original.nodes[0].config).toEqual({ event: 'OLD' })
    expect(toGraphDefinition({ definitionId: original.definitionId, dialect: original.dialect, title: '', original, nodes: [], edges: [] }).nodes).toEqual([])
  })

  it('maps known node types to tones', () => {
    expect(toneForType('journey.grant')).toBe('amber')
    expect(toneForType('offer.condition')).toBe('violet')
  })
})
