import { describe, expect, it } from 'vitest'
import { deliveryLabel, formatNullableCount, qualificationLabel, watermarkText } from './referralOperationsStatus'

describe('referral operations labels', () => {
  it('does not treat missing progress as a verified zero', () => {
    expect(formatNullableCount(null)).toBe('未计算')
    expect(formatNullableCount(1)).toBe('1')
  })

  it('does not treat BOUND or missing projection as qualified', () => {
    expect(qualificationLabel(null, null)).toBe('资格尚未投影')
    expect(qualificationLabel('QUALIFIED', true)).toContain('计入人数')
  })

  it('never labels delivery as 已到账', () => {
    for (const state of ['NOT_SUBMITTED', 'PENDING', 'UNKNOWN', 'ACCEPTED', 'SUCCEEDED', 'FAILED_FINAL']) {
      expect(deliveryLabel(state)).not.toContain('已到账')
    }
    expect(deliveryLabel('ACCEPTED')).toContain('非到账')
  })

  it('uses API asOf as watermark, not a local clock', () => {
    expect(watermarkText()).toBe('数据水位：未知')
    expect(watermarkText('2026-09-08T00:00:00Z', 'LIVE_DATABASE')).toContain('2026-09-08T00:00:00Z')
    expect(watermarkText('2026-09-08T00:00:00Z', 'LIVE_DATABASE')).toContain('LIVE_DATABASE')
  })
})
