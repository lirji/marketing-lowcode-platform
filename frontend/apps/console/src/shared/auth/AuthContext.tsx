import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts'
import { runtimeConfig } from '../config/runtime'
import { setAccessTokenProvider, setIdentityProvider, setUnauthorizedHandler } from '../api/client'
import { AuthStateContext, devAuthState, permits, type AuthState, type IdentityClaims } from './auth-context'
import { decodeJwtPayload, splitClaim } from './claims'

function claimsFromUser(user: User | null): IdentityClaims {
  const payload = user?.access_token ? decodeJwtPayload(user.access_token) : {}
  return {
    tenantId: String(payload.tenant_id ?? ''),
    organizations: splitClaim(payload.org_ids),
    shops: splitClaim(payload.shop_ids),
    permissions: splitClaim(payload.permissions),
  }
}

function returnToFromState(state: unknown) {
  if (typeof state === 'object' && state !== null && 'returnTo' in state) {
    const value = String((state as { returnTo: unknown }).returnTo)
    return value.startsWith('/') && !value.startsWith('//') ? value : '/'
  }
  return '/'
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  const manager = useMemo(() => runtimeConfig.authMode === 'OIDC' ? new UserManager({
    authority: runtimeConfig.oidcAuthority,
    client_id: runtimeConfig.oidcClientId,
    redirect_uri: `${window.location.origin}/auth/callback`,
    post_logout_redirect_uri: window.location.origin,
    response_type: 'code',
    scope: runtimeConfig.oidcScope,
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
    automaticSilentRenew: true,
    monitorSession: false,
    accessTokenExpiringNotificationTimeInSeconds: 60,
  }) : undefined, [])
  const [user, setUser] = useState<User | null>(null)
  const [ready, setReady] = useState(runtimeConfig.authMode === 'DEV')
  const [error, setError] = useState<string>()

  useEffect(() => {
    if (!manager) {
      setAccessTokenProvider(() => undefined)
      setIdentityProvider(() => ({ tenantId: devAuthState.tenantId, actorId: devAuthState.subject }))
      setUnauthorizedHandler(() => undefined)
      return
    }
    let active = true
    const applyUser = (next: User | null) => {
      if (!active) return
      setUser(next)
      setAccessTokenProvider(() => next?.access_token)
      const claims = claimsFromUser(next)
      setIdentityProvider(() => ({ tenantId: claims.tenantId, actorId: next?.profile.sub }))
      setReady(true)
    }
    const redirectToLogin = (returnTo: string) => manager.signinRedirect({ state: { returnTo } })
    const initialize = async () => {
      try {
        if (window.location.pathname === '/auth/callback') {
          const next = await manager.signinRedirectCallback()
          applyUser(next)
          navigate(returnToFromState(next.state), { replace: true })
          return
        }
        const current = await manager.getUser()
        if (!current || current.expired) {
          await redirectToLogin(`${window.location.pathname}${window.location.search}`)
          return
        }
        applyUser(current)
      } catch (cause) {
        if (!active) return
        setError(cause instanceof Error ? cause.message : 'OIDC 登录失败')
        setReady(true)
      }
    }
    const onLoaded = (next: User) => applyUser(next)
    const onExpired = () => void redirectToLogin(`${window.location.pathname}${window.location.search}`)
    manager.events.addUserLoaded(onLoaded)
    manager.events.addAccessTokenExpired(onExpired)
    setUnauthorizedHandler(() => void redirectToLogin(`${window.location.pathname}${window.location.search}`))
    void initialize()
    return () => {
      active = false
      manager.events.removeUserLoaded(onLoaded)
      manager.events.removeAccessTokenExpired(onExpired)
      setAccessTokenProvider(() => undefined)
      setUnauthorizedHandler(() => undefined)
    }
  }, [manager, navigate])

  if (!manager) {
    return <AuthStateContext.Provider value={devAuthState}>{children}</AuthStateContext.Provider>
  }

  const claims = claimsFromUser(user)
  const value: AuthState = {
    ready,
    authenticated: Boolean(user && !user.expired),
    displayName: String(user?.profile.name ?? user?.profile.preferred_username ?? '营销运营'),
    subject: user?.profile.sub ?? '',
    tenantId: claims.tenantId,
    organizations: claims.organizations,
    shops: claims.shops,
    permissions: claims.permissions,
    error,
    hasPermission: (permission) => permits(claims.permissions, permission),
    hasAnyPermission: (permissions) => permissions.some((permission) => permits(claims.permissions, permission)),
    login: () => manager.signinRedirect({ state: { returnTo: `${window.location.pathname}${window.location.search}` } }),
    logout: () => manager.signoutRedirect(),
  }
  if (!ready) return <div className="route-loading" role="status">正在验证运营身份…</div>
  if (error || !value.authenticated) {
    return (
      <div className="auth-error" role="alert">
        <h1>无法进入营销中枢</h1>
        <p>{error ?? '登录状态已失效。'}</p>
        <button className="button primary" type="button" onClick={() => void value.login()}>重新登录</button>
      </div>
    )
  }
  return <AuthStateContext.Provider value={value}>{children}</AuthStateContext.Provider>
}
