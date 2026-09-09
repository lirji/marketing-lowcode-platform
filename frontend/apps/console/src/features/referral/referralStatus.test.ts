import { describe, expect, it } from 'vitest'
import { assertNotPaidCopy, qualificationReasonLabel, qualificationStateLabel } from './referralStatus'

describe('referral status copy', () => {
  it('does not present pending or accepted states as paid', () => {
    expect(qualificationStateLabel('ELIGIBLE')).toContain('不是到账')
    expect(qualificationStateLabel('PENDING')).toBe('观察中 / 等待核验')
    expect(qualificationStateLabel('HELD')).toBe('状态待确认')
    expect(qualificationStateLabel('UNKNOWN_STATE')).toBe('状态待确认')
    expect(assertNotPaidCopy(qualificationStateLabel('ELIGIBLE'))).toBe(true)
    expect(assertNotPaidCopy(qualificationReasonLabel('OBSERVATION_PENDING'))).toBe(true)
  })

  it('maps known reasons and keeps unknown enums locatable', () => {
    expect(qualificationReasonLabel('NOT_NEW_CUSTOMER')).toBe('非新客')
    expect(qualificationReasonLabel('NET_BELOW_THRESHOLD')).toBe('退款后净额不足')
    expect(qualificationReasonLabel('SOME_NEW_CODE')).toBe('状态待确认')
  })
})
