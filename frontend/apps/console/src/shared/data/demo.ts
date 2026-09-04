import type { Campaign, NodeDefinition } from '../model/types'

export const demoCampaigns: Campaign[] = [
  { id: 'CMP-1101', name: '双11家电主会场', objective: '提升大家电成交与 PLUS 渗透', status: 'ACTIVE', organizationId: 'retail-business', shopId: 'home-appliance', createdBy: '王蕴', createdAt: '2026-08-28T09:12:00Z' },
  { id: 'CMP-1102', name: 'PLUS 超级会员日', objective: '会员复购与客单提升', status: 'IN_REVIEW', organizationId: 'membership', shopId: 'all-shops', createdBy: '赵辰', createdAt: '2026-08-31T03:22:00Z' },
  { id: 'CMP-1103', name: '新客首购礼', objective: '新注册用户七日首购', status: 'DRAFT', organizationId: 'growth', shopId: 'all-shops', createdBy: '林若君', createdAt: '2026-09-01T11:06:00Z' },
  { id: 'CMP-1104', name: '华南清凉家电季', objective: '区域库存周转', status: 'PAUSED', organizationId: 'regional-south', shopId: 'home-appliance', createdBy: '周荔', createdAt: '2026-08-19T02:30:00Z' },
]

export const demoNodeRegistry: NodeDefinition[] = [
  { stableTypeId: 'offer.scope', displayName: '商品范围', dialect: 'OFFER_DECISION_DAG', inputPorts: ['in'], outputPorts: ['matched', 'unmatched'], configSchema: { category: 'string', shop: 'string' } },
  { stableTypeId: 'offer.condition', displayName: '会员条件', dialect: 'OFFER_DECISION_DAG', inputPorts: ['in'], outputPorts: ['true', 'false'], configSchema: { field: 'string', equals: 'string' } },
  { stableTypeId: 'offer.benefit', displayName: '满减权益', dialect: 'OFFER_DECISION_DAG', inputPorts: ['in'], outputPorts: ['next'], configSchema: { thresholdMinor: 'integer', discountMinor: 'integer' } },
  { stableTypeId: 'journey.wait_timer', displayName: '等待时间', dialect: 'JOURNEY_STATE_MACHINE', inputPorts: ['in'], outputPorts: ['elapsed'], configSchema: { delaySeconds: 'integer' } },
  { stableTypeId: 'journey.send', displayName: '渠道触达', dialect: 'JOURNEY_STATE_MACHINE', inputPorts: ['in'], outputPorts: ['next'], configSchema: { templateId: 'string', channel: 'enum' } },
]
