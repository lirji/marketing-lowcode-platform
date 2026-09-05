import { describe, expect, it } from 'vitest'
import { returnToFromState } from './oidcCallback'

describe('oidc callback returnTo', () => {
  it('reads a safe in-app path from OIDC state', () => {
    expect(returnToFromState({ returnTo: '/releases' })).toBe('/releases')
    expect(returnToFromState({ returnTo: '//evil.example' })).toBe('/')
    expect(returnToFromState({ returnTo: '/login' })).toBe('/')
    expect(returnToFromState(undefined)).toBe('/')
  })
})
