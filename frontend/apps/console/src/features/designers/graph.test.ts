import { describe, expect, it } from 'vitest'
import { fromGraphDefinition, toGraphDefinition, toneForType } from './graph'
import type { DesignerNode } from './designerTypes'

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
    expect(restored.nodes.map((node) => node.id)).toEqual(['start', 'cond'])
    expect(restored.edges[0]?.source).toBe('start')
  })

  it('maps known node types to tones', () => {
    expect(toneForType('journey.grant')).toBe('amber')
    expect(toneForType('offer.condition')).toBe('violet')
  })
})
