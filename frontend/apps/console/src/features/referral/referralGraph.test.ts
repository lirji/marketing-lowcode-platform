import { describe, expect, it } from 'vitest'
import { REFERRAL_POLICY_DIALECT } from '../../shared/api/schemas'
import {
  emptyReferralDraft,
  emptyReward,
  fromReferralGraph,
  toReferralGraph,
  validateReferralDraft,
} from './referralGraph'

function completeDraft() {
  return {
    ...emptyReferralDraft({ organizationId: 'org-fixture', shopId: 'shop-fixture' }),
    startsAt: '2026-09-01T00:00:00Z',
    endsAt: '2026-09-10T00:00:00Z',
    settlementEndsAt: '2026-09-20T00:00:00Z',
    maxBindAgeSeconds: '100',
    goalType: 'FIRST_ORDER_SETTLED' as const,
    qualificationWindowSeconds: '100',
    minNetAmountMinor: '100',
    currency: 'CNY',
    observationSeconds: '10',
    lateArrivalGraceSeconds: '20',
    rewards: [
      emptyReward({ key: 'inviter', ruleId: 'inviter', role: 'INVITER', mode: 'PER_RELATION', threshold: '1', benefitDefinitionVersion: 'coupon-live@3', skuVersion: 'sku-active@2', perSubjectLimit: '10', campaignLimit: '100' }),
      emptyReward({ key: 'invitee', ruleId: 'invitee', role: 'INVITEE', mode: 'PER_RELATION', threshold: '1', benefitDefinitionVersion: 'coupon-live@3', skuVersion: 'sku-active@2', perSubjectLimit: '10', campaignLimit: '100' }),
      emptyReward({ key: 'three', ruleId: 'three', role: 'INVITER', mode: 'MILESTONE', threshold: '3', benefitDefinitionVersion: 'coupon-live@3', skuVersion: 'sku-active@2', perSubjectLimit: '10', campaignLimit: '100' }),
    ],
  }
}

describe('referral graph contract', () => {
  it('does not treat empty drafts as production policy', () => {
    const draft = emptyReferralDraft()
    expect(draft.maxBindAgeSeconds).toBe('')
    expect(draft.rewards).toEqual([])
    expect(validateReferralDraft(draft).length).toBeGreaterThan(0)
  })

  it('serializes the exact compiler field set and linear chain', () => {
    const graph = toReferralGraph({ definitionId: 'referral-cmp', draft: completeDraft(), title: '测试' })
    expect(graph.dialect).toBe(REFERRAL_POLICY_DIALECT)
    expect(graph.variables).toEqual({})
    expect(graph.nodes.map((node) => node.stableTypeId)).toEqual([
      'referral.start',
      'referral.bind',
      'referral.qualify',
      'referral.reward',
      'referral.reward',
      'referral.reward',
      'referral.end',
    ])
    expect(Object.keys(graph.nodes[0]?.config ?? {})).toEqual(['startsAt', 'endsAt', 'settlementEndsAt', 'organizationId', 'shopId'])
    expect(graph.nodes[1]?.config).toEqual({ attribution: 'FIRST_VALID_BIND', maxBindAgeSeconds: '100', inviteeScope: 'NEW_CUSTOMER' })
    expect(graph.nodes[3]?.config.quantity).toBe('1')
    expect(graph.edges).toHaveLength(6)
    expect(graph.edges.every((edge) => edge.sourcePort === 'next' && edge.targetPort === 'in')).toBe(true)
  })

  it('round-trips without dropping reward pins', () => {
    const graph = toReferralGraph({ definitionId: 'referral-cmp', draft: completeDraft() })
    const parsed = fromReferralGraph(graph)
    expect(parsed.errors).toEqual([])
    expect(parsed.draft.goalType).toBe('FIRST_ORDER_SETTLED')
    expect(parsed.draft.rewards.map((item) => item.ruleId)).toEqual(['inviter', 'invitee', 'three'])
    expect(parsed.draft.rewards[2]?.threshold).toBe('3')
    expect(parsed.draft.rewards[0]?.benefitDefinitionVersion).toBe('coupon-live@3')
  })

  it('rejects an empty shopId that would fail the compiler', () => {
    const draft = completeDraft()
    draft.shopId = ''
    expect(validateReferralDraft(draft).some((item) => item.field === 'shopId')).toBe(true)
  })

  it('rejects floats, empty rewards and invitee milestones', () => {
    const draft = completeDraft()
    draft.minNetAmountMinor = '10.5'
    draft.rewards[2] = { ...draft.rewards[2]!, role: 'INVITEE' }
    const errors = validateReferralDraft(draft)
    expect(errors.some((item) => item.field === 'minNetAmountMinor')).toBe(true)
    expect(errors.some((item) => item.message.includes('人数阶梯仅属于邀请人'))).toBe(true)
  })
})
