import type { Problem } from '../../shared/model/types'
import type { DesignerNode } from './designerTypes'

/** 优先使用后端明确 nodeId；图级错误不能虚构定位到第二个节点。 */
export function problemNodeId(problem: Problem, nodes: DesignerNode[]): string | undefined {
  if (problem.nodeId) return nodes.some((node) => node.id === problem.nodeId) ? problem.nodeId : undefined
  const index = /^\/nodes\/(\d+)(?:\/|$)/.exec(problem.pointer)?.[1]
  return index == null ? undefined : nodes[Number(index)]?.id
}
