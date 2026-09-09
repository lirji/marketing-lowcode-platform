import { describe, expect, it } from 'vitest'
import { parseCurrencyCode, parseReferralInstant, parseReferralInteger } from './referralIntegers'

describe('referral integer parsing', () => {
  it('rejects empty, float, leading zeros and unsafe values', () => {
    expect(parseReferralInteger('').ok).toBe(false)
    expect(parseReferralInteger('1.5').ok).toBe(false)
    expect(parseReferralInteger('01').ok).toBe(false)
    expect(parseReferralInteger(' 8').ok).toBe(false)
    expect(parseReferralInteger(String(Number.MAX_SAFE_INTEGER + 1)).ok).toBe(false)
    expect(parseReferralInteger('-3').ok).toBe(false)
  })

  it('accepts documented integer wires and optional negatives', () => {
    expect(parseReferralInteger('0')).toEqual({ ok: true, wire: '0' })
    expect(parseReferralInteger('100', { min: 1n }).ok).toBe(true)
    expect(parseReferralInteger('-8', { allowNegative: true })).toEqual({ ok: true, wire: '-8' })
    expect(parseReferralInteger('0', { min: 1n }).ok).toBe(false)
  })

  it('requires UTC instants and ISO currency', () => {
    expect(parseReferralInstant('2026-09-01T00:00:00Z')).toEqual({ ok: true, wire: '2026-09-01T00:00:00Z' })
    expect(parseReferralInstant('2026-09-01').ok).toBe(false)
    expect(parseCurrencyCode('CNY')).toEqual({ ok: true, wire: 'CNY' })
    expect(parseCurrencyCode('cny').ok).toBe(false)
  })
})
