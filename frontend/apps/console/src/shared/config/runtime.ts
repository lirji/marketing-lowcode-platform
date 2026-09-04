export type AuthMode = 'DEV' | 'OIDC'

export type RuntimeConfig = {
  apiBaseUrl: string
  demoMode: boolean
  authMode: AuthMode
  allowDevAuth: boolean
  oidcAuthority: string
  oidcClientId: string
  oidcScope: string
  requestTimeoutMs: number
}

export class RuntimeConfigError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'RuntimeConfigError'
  }
}

function booleanValue(value: boolean | string | undefined): boolean | undefined {
  if (typeof value === 'boolean') return value
  if (typeof value === 'string') {
    if (value.toLowerCase() === 'true') return true
    if (value.toLowerCase() === 'false') return false
  }
  return undefined
}

function originFromAuthority(authority: string): string {
  try {
    return new URL(authority).origin
  } catch {
    return ''
  }
}

function parseConfig(): RuntimeConfig {
  const injected = window.__MARKETING_CONFIG__
  const authMode = (injected?.AUTH_MODE ?? (import.meta.env.DEV ? import.meta.env.VITE_AUTH_MODE : undefined)) as AuthMode | undefined
  const demoMode = booleanValue(injected?.DEMO_MODE ?? (import.meta.env.DEV ? import.meta.env.VITE_DEMO_MODE : undefined))
  const allowDevAuth = booleanValue(injected?.ALLOW_DEV_AUTH) ?? import.meta.env.DEV

  if (authMode !== 'DEV' && authMode !== 'OIDC') {
    throw new RuntimeConfigError('营销控制台缺少合法 AUTH_MODE，已拒绝启动。')
  }
  if (demoMode === undefined) {
    throw new RuntimeConfigError('营销控制台缺少 DEMO_MODE，已拒绝启动。')
  }
  if (authMode === 'DEV' && import.meta.env.PROD && !allowDevAuth) {
    throw new RuntimeConfigError('生产构建禁止 DEV 身份。请设置 AUTH_MODE=OIDC，或显式允许 ALLOW_DEV_AUTH。')
  }
  if (authMode === 'OIDC') {
    const authority = injected?.OIDC_AUTHORITY ?? import.meta.env.VITE_OIDC_AUTHORITY
    const clientId = injected?.OIDC_CLIENT_ID ?? import.meta.env.VITE_OIDC_CLIENT_ID
    if (!authority || !clientId) {
      throw new RuntimeConfigError('OIDC 模式缺少 OIDC_AUTHORITY 或 OIDC_CLIENT_ID。')
    }
  }

  return Object.freeze({
    apiBaseUrl: injected?.API_BASE_URL ?? import.meta.env.VITE_API_BASE_URL ?? '',
    demoMode,
    authMode,
    allowDevAuth,
    oidcAuthority: injected?.OIDC_AUTHORITY ?? import.meta.env.VITE_OIDC_AUTHORITY ?? 'http://localhost:8180/realms/marketing',
    oidcClientId: injected?.OIDC_CLIENT_ID ?? import.meta.env.VITE_OIDC_CLIENT_ID ?? 'marketing-console',
    oidcScope: injected?.OIDC_SCOPE ?? 'openid profile email',
    requestTimeoutMs: 30_000,
  })
}

const placeholder: RuntimeConfig = Object.freeze({
  apiBaseUrl: '',
  demoMode: false,
  authMode: 'OIDC',
  allowDevAuth: false,
  oidcAuthority: '',
  oidcClientId: '',
  oidcScope: 'openid',
  requestTimeoutMs: 30_000,
})

let parsed: RuntimeConfig = placeholder
let parseError: RuntimeConfigError | undefined
try {
  parsed = parseConfig()
} catch (cause) {
  parseError = cause instanceof RuntimeConfigError ? cause : new RuntimeConfigError('营销控制台配置无效，已拒绝启动。')
}

export const runtimeConfig = parsed
export const runtimeConfigError = parseError
export const oidcOrigin = originFromAuthority(runtimeConfig.oidcAuthority)
