import type { Edge } from '@xyflow/react'
import type { Problem } from '../../shared/model/types'
import type { DesignerNode } from './designerTypes'

export const JOURNEY_UNBOUND_TITLE = 'Journey 草稿'
export const JOURNEY_UNBOUND_VERSION = '尚未保存'

export const journeyStarterNodes: DesignerNode[] = [
  {
    id: 'trigger',
    type: 'marketing',
    position: { x: 40, y: 145 },
    data: { label: '开始', subtitle: 'journey.trigger', tone: 'blue', config: {}, stableTypeId: 'journey.trigger' },
  },
]

export function journeyCanvas(): { nodes: DesignerNode[]; edges: Edge[]; problems: Problem[] } {
  return { nodes: journeyStarterNodes, edges: [], problems: [] }
}
