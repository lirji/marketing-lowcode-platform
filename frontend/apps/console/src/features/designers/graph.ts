import type { Edge } from '@xyflow/react'
import type { GraphDefinition } from '../../shared/api/schemas'
import type { DesignerNode } from './designerTypes'

const TYPE_BY_TONE: Record<DesignerNode['data']['tone'], string> = {
  slate: 'offer.start',
  blue: 'offer.condition',
  violet: 'offer.condition',
  teal: 'offer.fixed',
  amber: 'offer.fixed',
}

const DIALECT_TYPES: Record<string, Record<DesignerNode['data']['tone'], string>> = {
  OFFER_DECISION_DAG: TYPE_BY_TONE,
  JOURNEY_STATE_MACHINE: {
    slate: 'journey.wait-timer',
    blue: 'journey.trigger',
    violet: 'journey.condition',
    teal: 'journey.send',
    amber: 'journey.grant',
  },
}

export function toGraphDefinition(input: {
  definitionId: string
  dialect: string
  nodes: DesignerNode[]
  edges: Edge[]
  title: string
}): GraphDefinition {
  const types = DIALECT_TYPES[input.dialect] ?? TYPE_BY_TONE
  const nodes = input.nodes.map((node) => ({
    id: node.id,
    stableTypeId: node.data.stableTypeId ?? types[node.data.tone] ?? 'offer.condition',
    semanticVersion: '1.0.0',
    config: node.data.config,
  }))
  if (input.dialect === 'OFFER_DECISION_DAG' && !nodes.some((node) => node.stableTypeId === 'offer.end')) {
    nodes.push({ id: `${input.definitionId}-end`, stableTypeId: 'offer.end', semanticVersion: '1.0.0', config: {} })
  }
  const edges = input.edges.map((edge) => ({
    id: edge.id,
    sourceNodeId: edge.source,
    sourcePort: String(edge.sourceHandle ?? (types[toneOf(input.nodes, edge.source)] === 'offer.condition' || types[toneOf(input.nodes, edge.source)] === 'journey.condition' ? 'true' : 'next')),
    targetNodeId: edge.target,
    targetPort: String(edge.targetHandle ?? 'in'),
  }))
  return {
    definitionId: input.definitionId,
    dialect: input.dialect,
    dialectVersion: '1.0.0',
    nodes,
    edges,
    variables: {},
    annotations: { title: input.title, terms: input.title },
  }
}

function toneOf(nodes: DesignerNode[], id: string): DesignerNode['data']['tone'] {
  return nodes.find((node) => node.id === id)?.data.tone ?? 'slate'
}

const TONE_BY_TYPE: Record<string, DesignerNode['data']['tone']> = {
  'offer.start': 'slate',
  'offer.condition': 'violet',
  'offer.percentage': 'teal',
  'offer.fixed': 'teal',
  'offer.end': 'slate',
  'journey.trigger': 'blue',
  'journey.condition': 'violet',
  'journey.wait-event': 'blue',
  'journey.wait-timer': 'slate',
  'journey.send': 'teal',
  'journey.grant': 'amber',
  'journey.webhook': 'blue',
  'journey.end': 'slate',
}

export function toneForType(stableTypeId: string): DesignerNode['data']['tone'] {
  return TONE_BY_TYPE[stableTypeId] ?? (stableTypeId.includes('condition') ? 'violet' : 'slate')
}

export function fromGraphDefinition(graph: GraphDefinition): SnapshotLike {
  const nodes: DesignerNode[] = graph.nodes.filter((node) => node.stableTypeId !== 'offer.end').map((node, index) => ({
    id: node.id,
    type: 'marketing',
    position: { x: 40 + (index % 4) * 210, y: 80 + Math.floor(index / 4) * 140 },
    data: {
      label: node.stableTypeId.split('.').pop() ?? node.id,
      subtitle: node.stableTypeId,
      tone: toneForType(node.stableTypeId),
      config: node.config,
      stableTypeId: node.stableTypeId,
    },
  }))
  const edges: Edge[] = graph.edges.map((edge) => ({
    id: edge.id,
    source: edge.sourceNodeId,
    target: edge.targetNodeId,
    sourceHandle: edge.sourcePort,
    targetHandle: edge.targetPort,
    animated: true,
  }))
  return { nodes, edges }
}

type SnapshotLike = { nodes: DesignerNode[]; edges: Edge[] }
