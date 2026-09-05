import { createContext } from 'react'

export type IdentityClaims = {
  tenantId: string
  organizations: string[]
  shops: string[]
  permissions: string[]
}

export type AuthState = {
  ready: boolean
  authenticated: boolean
  displayName: string
  subject: string
  tenantId: string
  organizations: string[]
  shops: string[]
  permissions: string[]
  error?: string
  redirecting: boolean
  hasPermission: (permission: string) => boolean
  hasAnyPermission: (permissions: string[]) => boolean
  login: (returnTo?: string) => Promise<void>
  completeLogin: () => Promise<string>
  logout: () => Promise<void>
}

export function permits(permissions: string[], permission: string) {
  return permissions.includes('*') || permissions.includes(permission)
}

export const unauthenticatedState: AuthState = {
  ready: false,
  authenticated: false,
  displayName: '',
  subject: '',
  tenantId: '',
  organizations: [],
  shops: [],
  permissions: [],
  redirecting: false,
  hasPermission: () => false,
  hasAnyPermission: () => false,
  login: async () => undefined,
  completeLogin: async () => '/',
  logout: async () => undefined,
}

export const devAuthState: AuthState = {
  ready: true,
  authenticated: true,
  displayName: '林若君',
  subject: 'console-admin',
  tenantId: 'retail-cn',
  organizations: ['retail-business'],
  shops: ['all-shops'],
  permissions: ['*'],
  redirecting: false,
  hasPermission: () => true,
  hasAnyPermission: () => true,
  login: async () => undefined,
  completeLogin: async () => '/',
  logout: async () => undefined,
}

export const AuthStateContext = createContext<AuthState>(unauthenticatedState)
