import { describe, expect, it } from 'vitest'
import { businessTenantFromPayload } from './claims'

describe('businessTenantFromPayload', () => {
  it('prefers the top-level tenant_id claim', () => {
    expect(businessTenantFromPayload({
      tenant_id: 'retail-cn',
      owner: 'marketing-platform',
      properties: { tenant_id: 'ignored' },
    })).toBe('retail-cn')
  })

  it('reads Casdoor properties.tenant_id when the top-level claim is absent', () => {
    expect(businessTenantFromPayload({
      owner: 'marketing-platform',
      properties: { tenant_id: 'retail-cn' },
    })).toBe('retail-cn')
  })

  it('does not fall back to owner as the business tenant', () => {
    expect(businessTenantFromPayload({ owner: 'marketing-platform' })).toBe('')
  })
})
