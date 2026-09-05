import { describe, expect, it } from 'vitest'
import { sanitizeReturnTo, validateTenantSelection } from './tenantSelection'

describe('tenantSelection', () => {
  it('rejects unknown or mismatched Casdoor organizations', () => {
    expect(validateTenantSelection('', 'marketing-platform', 'ragshared0client00000001-org-marketing-platform')).toEqual({
      ok: false,
      message: '请输入所属组织',
    })
    expect(validateTenantSelection('retail-cn', '', 'marketing-console')).toEqual({
      ok: false,
      message: '未配置可用组织，请联系管理员',
    })
    expect(validateTenantSelection('other', 'marketing-platform', 'ragshared0client00000001-org-marketing-platform').ok).toBe(false)
    expect(validateTenantSelection('marketing-platform', 'marketing-platform', 'ragshared0client00000001-org-benefit-center').ok).toBe(false)
    expect(validateTenantSelection('marketing-platform', 'marketing-platform', 'ragshared0client00000001-org-marketing-platform')).toEqual({
      ok: true,
      organization: 'marketing-platform',
    })
  })

  it('allows generic OIDC clients that are not derived shared-app IDs', () => {
    expect(validateTenantSelection('retail-cn', 'retail-cn', 'marketing-console')).toEqual({
      ok: true,
      organization: 'retail-cn',
    })
  })

  it('only accepts in-app return paths', () => {
    expect(sanitizeReturnTo('/releases')).toBe('/releases')
    expect(sanitizeReturnTo('https://evil.example/')).toBe('/')
    expect(sanitizeReturnTo('//evil.example/')).toBe('/')
    expect(sanitizeReturnTo('/login')).toBe('/')
    expect(sanitizeReturnTo('/auth/callback')).toBe('/')
  })
})
