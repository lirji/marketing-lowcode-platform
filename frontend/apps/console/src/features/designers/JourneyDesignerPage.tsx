import type { Edge } from '@xyflow/react'
import { useSearchParams } from 'react-router-dom'
import { LowCodeDesigner, type DesignerNode } from './LowCodeDesigner'

const nodes: DesignerNode[] = [
  { id: 'trigger', type: 'marketing', position: { x: 10, y: 145 }, data: { label: '加购事件', subtitle: 'CartAdded v2', tone: 'blue', config: {} } },
  { id: 'wait', type: 'marketing', position: { x: 210, y: 145 }, data: { label: '等待 30m', subtitle: 'processing timer', tone: 'slate', config: { value: '1800' } } },
  { id: 'condition', type: 'marketing', position: { x: 415, y: 145 }, data: { label: '仍未支付？', subtitle: 'OrderPaid lookup', tone: 'violet', config: { field: 'eventType', value: 'OrderPaid' } } },
  { id: 'grant', type: 'marketing', position: { x: 625, y: 65 }, data: { label: '发召回券', subtitle: 'idempotent Grant', tone: 'amber', config: {} } },
  { id: 'push', type: 'marketing', position: { x: 825, y: 65 }, data: { label: 'Push 触达', subtitle: 'Consent + frequency', tone: 'teal', config: {} } },
  { id: 'goal', type: 'marketing', position: { x: 625, y: 255 }, data: { label: '目标：支付', subtitle: 'exit enrollment', tone: 'blue', config: {} } },
]
const edges: Edge[] = [
  { id: 'j1', source: 'trigger', target: 'wait', animated: true }, { id: 'j2', source: 'wait', target: 'condition', animated: true },
  { id: 'j3', source: 'condition', target: 'grant', label: 'yes', animated: true }, { id: 'j4', source: 'grant', target: 'push', animated: true },
  { id: 'j5', source: 'condition', target: 'goal', label: 'paid', animated: true },
]

export function JourneyDesignerPage() {
  const [params] = useSearchParams()
  const campaignId = params.get('campaignId') ?? undefined
  return <LowCodeDesigner title="加购未支付召回" version="Published v3 / Draft v4" dialect="JOURNEY_STATE_MACHINE" definitionId={campaignId ? `journey-${campaignId}` : 'journey-draft'} campaignId={campaignId} nodes={nodes} edges={edges} palette={[
    { label: '事件触发', subtitle: 'event-time + dedup', tone: 'blue' }, { label: '等待时间', subtitle: '可恢复 timer', tone: 'slate' },
    { label: '条件分支', subtitle: 'snapshot variables', tone: 'violet' }, { label: '发送消息', subtitle: 'consent gate', tone: 'teal' },
    { label: '发放权益', subtitle: 'stable command key', tone: 'amber' }, { label: 'Webhook', subtitle: 'signed connector', tone: 'blue' },
  ]} problems={[
    { code: 'LATE_SAFETY_TIMER', message: '等待事件需配置 processing-time safety timer', pointer: '/nodes/condition/config/safetyTimeout', severity: 'WARNING' },
  ]} />
}
