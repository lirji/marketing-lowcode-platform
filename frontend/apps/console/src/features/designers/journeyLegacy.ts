import type { NodeRegistryItem } from '../../shared/api/schemas'
import type { DesignerNode } from './designerTypes'
import { NODE_LABELS } from './nodeConfig'
import { toneForType } from './graph'

const JOURNEY_DIALECT = 'JOURNEY_STATE_MACHINE'
const LEGACY_WAIT_TIMER = 'journey.wait_timer'
const WAIT_TIMER = 'journey.wait-timer'
const WAIT_TIMER_VERSION = '1.0.0'

/** 只转换已核对的历史种子类型；未知版本仍由控制面拒绝，避免猜测节点语义。 */
export function legacyJourneyTimers(dialect: string, nodes: DesignerNode[]): DesignerNode[] {
  return dialect === JOURNEY_DIALECT
    ? nodes.filter(node => node.data.stableTypeId === LEGACY_WAIT_TIMER && node.data.semanticVersion === WAIT_TIMER_VERSION)
    : []
}

/** 当前后端必须真实支持目标类型，目录读取失败或缺少该版本时不能宣称可迁移。 */
export function supportsJourneyTimer(registry: NodeRegistryItem[] | undefined): boolean {
  return Boolean(registry?.some(node => node.stableTypeId === WAIT_TIMER && node.semanticVersion === WAIT_TIMER_VERSION
    && node.dialects.includes(JOURNEY_DIALECT)))
}

/** 草稿仅替换类型及展示信息，保留位置、ID、参数和语义版本；保存仍走不可变版本API。 */
export function migrateJourneyTimers(dialect: string, nodes: DesignerNode[]): DesignerNode[] {
  const ids = new Set(legacyJourneyTimers(dialect, nodes).map(node => node.id))
  return nodes.map(node => ids.has(node.id) ? { ...node, data: { ...node.data,
    stableTypeId: WAIT_TIMER, subtitle: WAIT_TIMER, label: NODE_LABELS[WAIT_TIMER], tone: toneForType(WAIT_TIMER),
  } } : node)
}
