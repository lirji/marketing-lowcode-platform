import type { Node } from '@xyflow/react'

export type DesignerNode = Node<{
  label: string
  subtitle: string
  tone: 'teal' | 'blue' | 'amber' | 'violet' | 'slate'
  config: Record<string, string>
  stableTypeId?: string
}, 'marketing'>
