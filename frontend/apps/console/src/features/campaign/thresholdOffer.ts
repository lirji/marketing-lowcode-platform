import type { AccountView, GraphDefinition } from '../../shared/api/schemas'

export const OFFER_DIALECT = 'OFFER_DECISION_DAG'
export const THRESHOLD_TEMPLATE = 'STORE_SKU_FIXED_DISCOUNT_V1'
export const CNY = 'CNY'
const BUDGET_ACCOUNT = /^BUDGET:([A-Za-z0-9_-]{1,128}):([A-Za-z0-9_-]{1,128}):CNY$/
const BUDGET_ACCOUNT_TYPE = 'BUDGET'
const ACTIVE_ACCOUNT_STATE = 'ACTIVE'
const skuPattern = /^[A-Za-z0-9_.:@/-]{1,128}$/
export const isOfferScopeId = (value: string) => skuPattern.test(value)

export const OFFER_VERSION_STATUS = {
  DRAFT: 'DRAFT', VALIDATED: 'VALIDATED', IN_REVIEW: 'IN_REVIEW',
  APPROVED: 'APPROVED', RELEASED: 'RELEASED', REJECTED: 'REJECTED',
} as const

export const offerStatusLabel: Record<string, string> = {
  [OFFER_VERSION_STATUS.DRAFT]: '草稿', [OFFER_VERSION_STATUS.VALIDATED]: '已校验',
  [OFFER_VERSION_STATUS.IN_REVIEW]: '审核中', [OFFER_VERSION_STATUS.APPROVED]: '已批准',
  [OFFER_VERSION_STATUS.RELEASED]: '已发布', [OFFER_VERSION_STATUS.REJECTED]: '已驳回',
}

export const offerOutcomeLabel: Record<string, string> = {
  APPLIED: '命中满减', THRESHOLD_NOT_MET: '未达到门槛', PRODUCT_SCOPE_MISMATCH: '商品或门店不在范围内',
  OUTSIDE_VALIDITY: '订单时间不在活动有效期内', NOT_APPLIED: '未应用优惠', CURRENCY_MISMATCH: '币种不一致',
}
export const yuan = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: CNY })

export function campaignWorkbenchPath(campaignId: string) {
  return `/campaigns/${encodeURIComponent(campaignId)}/workbench`
}

export function minorFromYuan(value: string): string | undefined {
  const match = /^(\d{1,12})(?:\.(\d{1,2}))?$/.exec(value.trim())
  if (!match) return undefined
  return String(BigInt(match[1]) * 100n + BigInt((match[2] ?? '').padEnd(2, '0')))
}

export function activeCampaignBudget(account: AccountView) {
  return account.type === BUDGET_ACCOUNT_TYPE && account.state === ACTIVE_ACCOUNT_STATE && account.currency === CNY && BUDGET_ACCOUNT.test(account.resourceKey)
}

export function thresholdGraph(input: {
  campaignId: string; shopId: string; skuId: string; benefit: string; budgetKey: string
  threshold: string; discount: string; validFrom: string; validTo: string
}): GraphDefinition | undefined {
  const threshold = minorFromYuan(input.threshold), amount = minorFromYuan(input.discount)
  const funder = BUDGET_ACCOUNT.exec(input.budgetKey)
  if (!threshold || !amount || Number(amount) <= 0 || Number(amount) > Number(threshold) || !funder
    || !isOfferScopeId(input.skuId) || !isOfferScopeId(input.shopId) || !input.benefit
    || !(Date.parse(input.validFrom) < Date.parse(input.validTo))) return undefined

  const definitionId = `offer-${input.campaignId}`
  const node = (id: string, stableTypeId: string, config: Record<string, string> = {}) => ({ id, stableTypeId, semanticVersion: '1.0.0', config })
  const edge = (sourceNodeId: string, targetNodeId: string) => ({ id: `${sourceNodeId}-${targetNodeId}`, sourceNodeId, targetNodeId, sourcePort: 'next', targetPort: 'in' })
  return {
    definitionId, dialect: OFFER_DIALECT, dialectVersion: '1.0.0',
    nodes: [node('start', 'offer.start'), node('discount', 'offer.fixed', {
      offerId: `${definitionId}-fixed`, thresholdMinor: threshold, amountMinor: amount,
      shopIds: input.shopId, skuIds: input.skuId, stage: 'SHOP', pricingCurrency: CNY, benefitDefinitionVersion: input.benefit,
      fundingRules: `${funder[1]}|${funder[2]}|10000`, validFrom: input.validFrom, validTo: input.validTo,
    }), node('end', 'offer.end')],
    edges: [edge('start', 'discount'), edge('discount', 'end')], variables: {},
    annotations: { template: THRESHOLD_TEMPLATE, title: '指定门店 SKU 满减' },
  }
}
