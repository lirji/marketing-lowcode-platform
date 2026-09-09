import { parseReferralInstant, parseReferralInteger } from './referralIntegers'
import type { FieldError } from './referralGraph'

export const REFERRAL_SIMULATION_FIELDS = [
  'boundAt',
  'now',
  'relationId',
  'validCount',
  'verified',
  'newCustomerAtBind',
  'firstValidOrder',
  'qualifyingFactAt',
  'qualifyingFactReceivedAt',
  'settledAmountMinor',
  'cumulativeRefundMinor',
  'currency',
] as const

export type ReferralSimulationFacts = Record<(typeof REFERRAL_SIMULATION_FIELDS)[number], string>

export function emptySimulationFacts(currency = ''): ReferralSimulationFacts {
  return {
    boundAt: '',
    now: '',
    relationId: '',
    validCount: '',
    verified: '',
    newCustomerAtBind: '',
    firstValidOrder: '',
    qualifyingFactAt: '',
    qualifyingFactReceivedAt: '',
    settledAmountMinor: '',
    cumulativeRefundMinor: '',
    currency,
  }
}

/** 控制面测试示例，不是已批准生产参数。 */
export function documentedSimulationExample(): ReferralSimulationFacts {
  return {
    boundAt: '2026-09-01T00:00:10Z',
    now: '2026-09-01T00:00:30Z',
    relationId: 'relation-fixture',
    validCount: '5',
    verified: 'true',
    newCustomerAtBind: 'YES',
    firstValidOrder: 'YES',
    qualifyingFactAt: '2026-09-01T00:00:20Z',
    qualifyingFactReceivedAt: '2026-09-01T00:00:20Z',
    settledAmountMinor: '100',
    cumulativeRefundMinor: '0',
    currency: 'CNY',
  }
}

export function validateSimulationFacts(facts: ReferralSimulationFacts): FieldError[] {
  const errors: FieldError[] = []
  const section = '仿真输入'
  const boundAt = parseReferralInstant(facts.boundAt)
  if (!boundAt.ok) errors.push({ field: 'boundAt', section, message: boundAt.error })
  const now = parseReferralInstant(facts.now)
  if (!now.ok) errors.push({ field: 'now', section, message: now.error })
  if (!facts.relationId.trim()) errors.push({ field: 'relationId', section, message: '关系 ID 不能为空' })
  const count = parseReferralInteger(facts.validCount, { min: 0n })
  if (!count.ok) errors.push({ field: 'validCount', section, message: count.error })
  if (facts.verified !== 'true' && facts.verified !== 'false') {
    errors.push({ field: 'verified', section, message: 'verified 只能是字符串 true 或 false' })
  }
  for (const key of ['newCustomerAtBind', 'firstValidOrder'] as const) {
    if (!['YES', 'NO', 'UNKNOWN'].includes(facts[key])) {
      errors.push({ field: key, section, message: `${key} 只能是 YES / NO / UNKNOWN` })
    }
  }
  for (const key of ['qualifyingFactAt', 'qualifyingFactReceivedAt'] as const) {
    if (facts[key] !== '') {
      const instant = parseReferralInstant(facts[key])
      if (!instant.ok) errors.push({ field: key, section, message: instant.error })
    }
  }
  const settled = parseReferralInteger(facts.settledAmountMinor, { allowNegative: true })
  if (!settled.ok) errors.push({ field: 'settledAmountMinor', section, message: settled.error })
  const refund = parseReferralInteger(facts.cumulativeRefundMinor, { allowNegative: true })
  if (!refund.ok) errors.push({ field: 'cumulativeRefundMinor', section, message: refund.error })
  if (!facts.currency.trim()) errors.push({ field: 'currency', section, message: '币种不能为空' })
  return errors
}

export function simulationFactsWire(facts: ReferralSimulationFacts): Record<string, string> {
  const errors = validateSimulationFacts(facts)
  if (errors.length > 0) throw new Error(errors.map((item) => item.message).join('；'))
  return { ...facts }
}
