import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { flushSync } from 'react-dom'
import { useLocation, useNavigate } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts'
import { runtimeConfig } from '../config/runtime'
import { invalidateApiSession, setAccessTokenProvider, setIdentityProvider, setUnauthorizedHandler } from '../api/client'
import { AuthStateContext, devAuthState, permits, type AuthState, type IdentityClaims } from './auth-context'
import { businessTenantFromPayload, decodeJwtPayload, permissionNames, splitClaim } from './claims'
import { getDevTenantId } from './devTenant'
import { completeOidcRedirect, returnToFromState } from './oidcCallback'
import { sanitizeReturnTo } from './tenantSelection'

function claimsFromUser(user: User | null): IdentityClaims {
  const payload = user?.access_token ? decodeJwtPayload(user.access_token) : {}
  const owner = String(payload.owner ?? '').trim()
  const tenantId = businessTenantFromPayload(payload)
  const organizations = splitClaim(payload.org_ids)
  return {
    tenantId,
    organizations: organizations.length > 0 ? organizations : owner ? [owner] : [],
    shops: splitClaim(payload.shop_ids),
    permissions: permissionNames(payload.permissions),
  }
}

/** 只在权限范围变化时重建页面；普通令牌刷新及声明排序变化保留当前编辑。 */
function oidcSessionScope(user: User | null) {
  if (!user) return 'anonymous'
  const claims = claimsFromUser(user)
  const ordered = (values: string[]) => [...new Set(values)].sort()
  return JSON.stringify([user.profile.sub, claims.tenantId, ordered(claims.organizations), ordered(claims.shops), ordered(claims.permissions)])
}

const OIDC_SIGNOUT_FAILED = '已退出本机会话，但统一身份服务退出未完成。请重试登录或联系管理员。'

function DevAuthProvider({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [tenantId, setTenantId] = useState(getDevTenantId)
  const [authenticated, setAuthenticated] = useState(true)
  const [sessionVersion, setSessionVersion] = useState(0)
  const [transitioning, setTransitioning] = useState(false)
  const clearSession = useCallback(async () => {
    setIdentityProvider(() => ({}))
    invalidateApiSession()
    // 先卸载观察者，再清缓存，避免旧页面在退出途中重新发起查询并填回旧数据。
    flushSync(() => setTransitioning(true))
    await queryClient.cancelQueries()
    queryClient.clear()
  }, [queryClient])
  const login = useCallback(async (returnTo = '/') => {
    const next = getDevTenantId()
    await clearSession()
    setTenantId(next)
    setAuthenticated(true)
    setIdentityProvider(() => ({ tenantId: next, actorId: devAuthState.subject }))
    // 同租户重新登录也要重置草稿和 mutation 观察者，迟到结果不能进入新会话。
    setSessionVersion((version) => version + 1)
    navigate(sanitizeReturnTo(returnTo), { replace: true })
    setTransitioning(false)
  }, [navigate, clearSession])
  const logout = useCallback(async () => {
    await clearSession()
    setAuthenticated(false)
    setSessionVersion((version) => version + 1)
    navigate('/login', { replace: true })
    setTransitioning(false)
  }, [navigate, clearSession])
  const value: AuthState = {
    ...devAuthState,
    authenticated,
    tenantId,
    organizations: tenantId === 'retail-cn' ? ['retail-business'] : [tenantId],
    login,
    completeLogin: async () => '/',
    logout,
  }
  useEffect(() => {
    setAccessTokenProvider(() => undefined)
    setIdentityProvider(() => ({ tenantId, actorId: devAuthState.subject }))
    setUnauthorizedHandler(() => undefined)
  }, [tenantId])
  return <AuthStateContext.Provider key={`${tenantId}:${sessionVersion}`} value={value}>{transitioning ? null : children}</AuthStateContext.Provider>
}

export function AuthProvider({ children }: { children: ReactNode }) {
  if (runtimeConfig.authMode !== 'OIDC') {
    return <DevAuthProvider>{children}</DevAuthProvider>
  }
  return <OidcAuthProvider>{children}</OidcAuthProvider>
}

function OidcAuthProvider({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  const navigateRef = useRef(navigate)
  navigateRef.current = navigate
  const location = useLocation()
  const queryClient = useQueryClient()
  const manager = useMemo(() => new UserManager({
    authority: runtimeConfig.oidcAuthority,
    client_id: runtimeConfig.oidcClientId,
    redirect_uri: `${window.location.origin}/auth/callback`,
    post_logout_redirect_uri: `${window.location.origin}/login`,
    response_type: 'code',
    scope: runtimeConfig.oidcScope,
    loadUserInfo: false,
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
    automaticSilentRenew: false,
    monitorSession: false,
  }), [])
  const [user, setUser] = useState<User | null>(null)
  const [ready, setReady] = useState(false)
  const [redirecting, setRedirecting] = useState(false)
  const [error, setError] = useState<string>()
  const signingOut = useRef(false)
  const remoteSignoutPending = useRef(false)
  const sessionScope = useRef<string | undefined>(undefined)
  const sessionEpoch = useRef(0)
  const [sessionVersion, setSessionVersion] = useState(0)
  const [transitioning, setTransitioning] = useState(false)

  const applyUser = useCallback((candidate: User | null, forceReset = false) => {
    const next = candidate && !candidate.expired ? candidate : null
    const nextScope = oidcSessionScope(next)
    sessionEpoch.current++
    if (forceReset || nextScope !== sessionScope.current) {
      // 先撤销身份和旧回执，再卸载查询/mutation观察者，避免旧草稿或迟到结果进入新范围。
      setAccessTokenProvider(() => undefined)
      setIdentityProvider(() => ({}))
      invalidateApiSession()
      flushSync(() => setTransitioning(true))
      // cancelQueries同步取消各查询；clear会移除对象，旧Promise完成也不能重新填充缓存。
      void queryClient.cancelQueries()
      queryClient.clear()
      sessionScope.current = nextScope
      setSessionVersion((version) => version + 1)
    }
    setUser(next)
    setAccessTokenProvider(() => next?.access_token)
    const claims = claimsFromUser(next)
    setIdentityProvider(() => next ? { tenantId: claims.tenantId, actorId: next.profile.sub } : {})
    setReady(true)
    setTransitioning(remoteSignoutPending.current)
  }, [queryClient])

  useEffect(() => {
    let active = true
    const initialize = async () => {
      const epoch = sessionEpoch.current
      try {
        if (window.location.pathname === '/auth/callback') {
          setReady(true)
          return
        }
        const current = await manager.getUser()
        // storage读取可能比userLoaded事件慢；已经到达的新身份优先。
        if (!active || signingOut.current || epoch !== sessionEpoch.current) return
        applyUser(current && !current.expired ? current : null)
      } catch (cause) {
        if (!active || signingOut.current || epoch !== sessionEpoch.current) return
        setError(cause instanceof Error ? cause.message : 'OIDC 登录失败')
        setReady(true)
      }
    }
    const onLoaded = (next: User) => { if (!signingOut.current) applyUser(next) }
    const onExpired = () => applyUser(null)
    manager.events.addUserLoaded(onLoaded)
    manager.events.addAccessTokenExpired(onExpired)
    manager.events.addUserUnloaded(onExpired)
    manager.events.addUserSignedOut(onExpired)
    setUnauthorizedHandler(() => {
      if (signingOut.current) return
      signingOut.current = true
      applyUser(null, true)
      void manager.removeUser().catch(() => { if (signingOut.current && sessionScope.current === 'anonymous') setError(OIDC_SIGNOUT_FAILED) })
      const path = window.location.pathname
      if (path === '/login' || path === '/auth/callback') return
      const returnTo = `${path}${window.location.search}`
      navigateRef.current(`/login?returnTo=${encodeURIComponent(sanitizeReturnTo(returnTo))}`, { replace: true })
    })
    void initialize()
    return () => {
      active = false
      manager.events.removeUserLoaded(onLoaded)
      manager.events.removeAccessTokenExpired(onExpired)
      manager.events.removeUserUnloaded(onExpired)
      manager.events.removeUserSignedOut(onExpired)
      sessionEpoch.current++
      invalidateApiSession()
      setAccessTokenProvider(() => undefined)
      setIdentityProvider(() => ({}))
      setUnauthorizedHandler(() => undefined)
    }
  }, [manager, applyUser, queryClient])

  const login = useCallback(async (returnTo = '/') => {
    signingOut.current = false
    setError(undefined)
    setRedirecting(true)
    try {
      await manager.signinRedirect({ state: { returnTo: sanitizeReturnTo(returnTo) } })
    } catch (cause) {
      setRedirecting(false)
      setError(cause instanceof Error ? cause.message : '无法连接统一身份服务，请确认 Casdoor 已启动后重试。')
    }
  }, [manager])

  const completeLogin = useCallback(async () => {
    signingOut.current = false
    const epoch = sessionEpoch.current
    const next = await completeOidcRedirect(manager)
    if (signingOut.current) return '/login'
    // 回调可能已通过userLoaded安装身份；较新事件不能被旧回调覆盖。
    if (epoch === sessionEpoch.current) applyUser(next)
    else if (sessionScope.current !== oidcSessionScope(next)) return '/'
    return returnToFromState(next.state)
  }, [manager, applyUser])

  const logout = useCallback(async () => {
    signingOut.current = true
    remoteSignoutPending.current = true
    applyUser(null, true)
    // 保留OIDC存储供库生成id_token_hint；本机身份已撤销，远端失败也不恢复旧会话。
    try {
      await manager.signoutRedirect()
    } catch {
      if (!signingOut.current || sessionScope.current !== 'anonymous') return
      setError(OIDC_SIGNOUT_FAILED)
      navigateRef.current('/login', { replace: true })
    } finally {
      // OIDC库在重定向失败时调用window.stop；等待其结束再加载登录页，避免中断懒加载。
      remoteSignoutPending.current = false
      setTransitioning(false)
    }
  }, [manager, applyUser])

  const claims = useMemo(() => claimsFromUser(user), [user])
  const permissions = claims.permissions
  const hasPermission = useCallback((permission: string) => permits(permissions, permission), [permissions])
  const hasAnyPermission = useCallback(
    (required: string[]) => required.some((permission) => permits(permissions, permission)),
    [permissions],
  )

  const value = useMemo<AuthState>(() => ({
    ready,
    authenticated: Boolean(user && !user.expired),
    displayName: String(user?.profile.name ?? user?.profile.preferred_username ?? '营销运营'),
    subject: user?.profile.sub ?? '',
    tenantId: claims.tenantId,
    organizations: claims.organizations,
    shops: claims.shops,
    permissions,
    error,
    redirecting,
    hasPermission,
    hasAnyPermission,
    login: (returnTo = `${location.pathname}${location.search}`) => login(returnTo),
    completeLogin,
    logout,
  }), [
    ready, user, claims, permissions, error, redirecting, hasPermission, hasAnyPermission,
    login, completeLogin, logout, location.pathname, location.search,
  ])

  return <AuthStateContext.Provider key={sessionVersion} value={value}>{transitioning ? <p role="status">正在切换会话…</p> : children}</AuthStateContext.Provider>
}
