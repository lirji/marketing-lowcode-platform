import { describe, expect, it } from 'vitest'
import { isReferralSimulation, parseSimulationResult, referralParticipantPageSchema } from './schemas'

describe('simulation result union', () => {
  it('keeps the old offer dialect parseable', () => {
    const value = parseSimulationResult({
      subtotalMinor: 68800,
      discountMinor: 800,
      payableMinor: 68000,
      trace: [{ nodeId: 'n1', nodeType: 'offer.fixed', outcome: 'discount=800' }],
    })
    expect(isReferralSimulation(value)).toBe(false)
    if (!isReferralSimulation(value)) expect(value.payableMinor).toBe(68000)
  })

  it('parses referral preview without inventing paid success', () => {
    const value = parseSimulationResult({
      simulationOnly: true,
      evidenceAuthority: 'SIMULATED_INPUT',
      qualification: { state: 'PENDING', reason: 'OBSERVATION_PENDING', dueAt: '2026-09-01T00:00:30Z' },
      validCount: 5,
      rewardCandidates: [],
    })
    expect(isReferralSimulation(value)).toBe(true)
    if (isReferralSimulation(value)) {
      expect(value.simulationOnly).toBe(true)
      expect(value.qualification.state).toBe('PENDING')
    }
  })

  it('parses operations pages without inventing paid success', () => {
    const page = referralParticipantPageSchema.parse({
      items: [{
        participantId: 'p-1',
        campaignId: 'campaign',
        organizationId: 'org',
        shopId: 'shop',
        definitionId: 'def',
        definitionVersion: 1,
        generation: 1,
        state: 'ACTIVE',
        createdAt: '2026-09-01T00:00:00Z',
        validCount: null,
        everQualifiedCount: 2,
        progressRevision: 3,
      }],
      nextCursor: 'p-1',
      asOf: '2026-09-08T00:00:00Z',
      consistency: 'LIVE_DATABASE',
    })
    expect(page.items[0]?.validCount).toBeNull()
    expect(page.consistency).toBe('LIVE_DATABASE')
    const omitted = referralParticipantPageSchema.parse({
      items: [{
        participantId: 'p-2',
        campaignId: 'campaign',
        organizationId: 'org',
        shopId: 'shop',
        definitionId: 'def',
        definitionVersion: 1,
        generation: 1,
        state: 'ACTIVE',
        createdAt: '2026-09-01T00:00:00Z',
      }],
      nextCursor: null,
      asOf: '2026-09-08T00:00:00Z',
      consistency: 'LIVE_DATABASE',
    })
    expect(omitted.items[0]?.validCount).toBeUndefined()
  })
})
