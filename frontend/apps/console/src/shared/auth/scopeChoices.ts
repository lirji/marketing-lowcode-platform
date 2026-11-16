import type { CampaignType } from '../model/campaignType'
/**
 * 把身份声明转成创建活动可用的组织/店铺选项。
 * JWT 未声明店铺或含 * 视为全域，提交空 shopId；切勿伪造 all-shops，
 * 否则控制面 requireShop 会报 shop is outside authenticated scope。
 */
export function orgChoicesFromIdentity(organizations: string[]): string[] {
  return organizations.filter((id) => id.trim() && id !== '*')
}

export function shopChoicesFromIdentity(shops: string[]): { value: string; label: string }[] {
  const concrete = shops.filter((id) => id.trim() && id !== '*')
  if (shops.includes('*') || concrete.length === 0) {
    return [{ value: '', label: '全部店铺' }]
  }
  return concrete.map((id) => ({ value: id, label: id }))
}

/** Casdoor 未声明店铺时省略 shopId；不要带空字符串或伪造 all-shops。 */
export function campaignCreateBody(payload: { name: string; objective: string; organizationId: string; shopId: string; campaignType?: CampaignType }) {
  const shopId = payload.shopId.trim()
  const type = payload.campaignType ? { campaignType: payload.campaignType } : {}
  return shopId
    ? { ...type, name: payload.name, objective: payload.objective, organizationId: payload.organizationId, shopId }
    : { ...type, name: payload.name, objective: payload.objective, organizationId: payload.organizationId }
}
