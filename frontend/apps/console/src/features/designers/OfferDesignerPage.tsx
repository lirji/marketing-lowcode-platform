import type { Edge } from '@xyflow/react'
import { useSearchParams } from 'react-router-dom'
import { LowCodeDesigner, type DesignerNode } from './LowCodeDesigner'

const nodes: DesignerNode[] = [
  { id: 'start', type: 'marketing', position: { x: 10, y: 165 }, data: { label: '开始', subtitle: 'request context', tone: 'slate', config: {} } },
  { id: 'scope', type: 'marketing', position: { x: 210, y: 75 }, data: { label: '家电类目', subtitle: 'Scope · category', tone: 'blue', config: { field: 'product.category', value: 'appliance' } } },
  { id: 'member', type: 'marketing', position: { x: 420, y: 75 }, data: { label: 'PLUS 会员', subtitle: 'Condition · exact', tone: 'violet', config: { field: 'member.level', value: 'PLUS' } } },
  { id: 'coupon', type: 'marketing', position: { x: 420, y: 260 }, data: { label: '平台券', subtitle: 'Compatible benefit', tone: 'amber', config: {} } },
  { id: 'discount', type: 'marketing', position: { x: 680, y: 160 }, data: { label: '满 500 减 80', subtitle: 'Funding 60 / 40', tone: 'teal', config: { field: 'cart.amount', value: '50000' } } },
]
const edges: Edge[] = [
  { id: 'e1', source: 'start', target: 'scope', animated: true }, { id: 'e2', source: 'scope', target: 'member', animated: true },
  { id: 'e3', source: 'member', target: 'discount', label: 'matched', animated: true }, { id: 'e4', source: 'coupon', target: 'discount', label: 'compatible', animated: true },
]

export function OfferDesignerPage() {
  const [params] = useSearchParams()
  const campaignId = params.get('campaignId') ?? undefined
  return <LowCodeDesigner title="双11家电主会场 · Offer" version="Draft v8" dialect="OFFER_DECISION_DAG" definitionId={campaignId ? `offer-${campaignId}` : 'offer-draft'} campaignId={campaignId} nodes={nodes} edges={edges} palette={[
    { label: '商品范围', subtitle: '类目 / 店铺 / 品牌', tone: 'blue' }, { label: '条件分支', subtitle: 'typed predicate', tone: 'violet' },
    { label: '优惠候选', subtitle: '满减 / 折扣 / 一口价', tone: 'teal' }, { label: '权益候选', subtitle: '券 / 积分 / 赠品', tone: 'amber' },
    { label: '互斥策略', subtitle: '兼容 / 择优 / 封顶', tone: 'slate' },
  ]} problems={[
    { code: 'TERMS_MISSING', message: '平台券缺少用户可见公示文案', pointer: '/nodes/coupon/config/terms', severity: 'ERROR' },
    { code: 'AUDIENCE_STALE_POLICY', message: '会员字段建议声明陈旧降级路径', pointer: '/nodes/member/config/stalePolicy', severity: 'WARNING' },
  ]} />
}
