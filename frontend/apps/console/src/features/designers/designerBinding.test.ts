import { describe, expect, it } from 'vitest'
import { campaignNameFromLocationState, designerHeading } from './designerBinding'

describe('designerHeading', () => {
  it('uses the campaign name instead of the demo title when bound', () => {
    expect(designerHeading({
      campaignId: 'cmp-1',
      campaignName: '春季会员日',
      boundSuffix: 'Offer',
      demoTitle: '双11家电主会场 · Offer',
      demoVersion: 'Draft v8',
    })).toEqual({
      title: '春季会员日 · Offer',
      version: '尚未保存',
      bound: true,
    })
  })

  it('does not fall back to 双11 when the campaign name is still loading', () => {
    expect(designerHeading({
      campaignId: 'cmp-1',
      boundSuffix: 'Offer',
      demoTitle: '双11家电主会场 · Offer',
      demoVersion: 'Draft v8',
    })).toEqual({
      title: 'Offer 草稿',
      version: '尚未保存',
      bound: true,
    })
  })

  it('uses the unbound title when no campaign is bound', () => {
    expect(designerHeading({
      demoTitle: 'Offer 草稿',
      demoVersion: '尚未保存',
      boundSuffix: 'Offer',
    }).title).toBe('Offer 草稿')
  })
})

describe('campaignNameFromLocationState', () => {
  it('reads the name passed from create/open navigation', () => {
    expect(campaignNameFromLocationState({ campaignName: '春季会员日' })).toBe('春季会员日')
    expect(campaignNameFromLocationState({})).toBeUndefined()
  })
})
