import { describe, expect, it } from 'vitest'
import { campaignCreateBody, orgChoicesFromIdentity, shopChoicesFromIdentity } from './scopeChoices'

describe('shopChoicesFromIdentity', () => {
  it('does not invent all-shops when Casdoor token has no shop_ids', () => {
    expect(shopChoicesFromIdentity([])).toEqual([{ value: '', label: '全部店铺' }])
  })

  it('treats wildcard as all shops', () => {
    expect(shopChoicesFromIdentity(['*'])).toEqual([{ value: '', label: '全部店铺' }])
    expect(shopChoicesFromIdentity(['*', 'east-shop'])).toEqual([{ value: '', label: '全部店铺' }])
  })

  it('keeps explicit DEV shop ids including all-shops', () => {
    expect(shopChoicesFromIdentity(['all-shops'])).toEqual([{ value: 'all-shops', label: 'all-shops' }])
    expect(shopChoicesFromIdentity(['east-shop', 'west-shop'])).toEqual([
      { value: 'east-shop', label: 'east-shop' },
      { value: 'west-shop', label: 'west-shop' },
    ])
  })
})

describe('orgChoicesFromIdentity', () => {
  it('drops wildcard and does not invent retail-business', () => {
    expect(orgChoicesFromIdentity([])).toEqual([])
    expect(orgChoicesFromIdentity(['*'])).toEqual([])
    expect(orgChoicesFromIdentity(['marketing-platform'])).toEqual(['marketing-platform'])
  })
})

describe('campaignCreateBody', () => {
  it('omits blank shopId so control-plane skips requireShop', () => {
    expect(campaignCreateBody({
      name: '双11',
      objective: '提升大家电成交',
      organizationId: 'marketing-platform',
      shopId: '  ',
    })).toEqual({
      name: '双11',
      objective: '提升大家电成交',
      organizationId: 'marketing-platform',
    })
  })

  it('keeps a concrete shop id', () => {
    expect(campaignCreateBody({
      name: '双11',
      objective: '提升大家电成交',
      organizationId: 'marketing-platform',
      shopId: 'east-shop',
    })).toEqual({
      name: '双11',
      objective: '提升大家电成交',
      organizationId: 'marketing-platform',
      shopId: 'east-shop',
    })
  })
})
