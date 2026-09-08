import type { Edge } from '@xyflow/react'
import type { Problem } from '../../shared/model/types'
import type { DesignerNode } from './designerTypes'

export const OFFER_UNBOUND_TITLE = 'Offer 草稿'
export const OFFER_UNBOUND_VERSION = '尚未保存'

export const offerStarterNodes: DesignerNode[] = [
  {
    id: 'start',
    type: 'marketing',
    position: { x: 40, y: 160 },
    data: { label: '开始', subtitle: 'offer.start', tone: 'slate', config: {}, stableTypeId: 'offer.start' },
  },
]

export function offerCanvas(): { nodes: DesignerNode[]; edges: Edge[]; problems: Problem[] } {
  return { nodes: offerStarterNodes, edges: [], problems: [] }
}
