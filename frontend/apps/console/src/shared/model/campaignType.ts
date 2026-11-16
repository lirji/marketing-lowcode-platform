/** 与控制面 Campaign.Type 的稳定 API 编码一致，未知值不能猜测为标准活动。 */
export const CAMPAIGN_TYPE = { STANDARD: 'STANDARD', REFERRAL: 'REFERRAL' } as const
export type CampaignType = typeof CAMPAIGN_TYPE[keyof typeof CAMPAIGN_TYPE]
export function isKnownCampaignType(value: string | undefined): value is CampaignType {
  return value === CAMPAIGN_TYPE.STANDARD || value === CAMPAIGN_TYPE.REFERRAL
}
