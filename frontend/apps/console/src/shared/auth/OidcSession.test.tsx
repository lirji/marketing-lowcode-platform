import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { User } from 'oidc-client-ts'
import { StrictMode, useState } from 'react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiProblem } from '../api/client'
import { AuthProvider } from './AuthContext'
import { useAuth } from './useAuth'

const oidc = vi.hoisted(() => ({
  getUser: vi.fn(), removeUser: vi.fn(), signoutRedirect: vi.fn(), signinRedirect: vi.fn(), callback: vi.fn(), completed: vi.fn(),
  loaded: new Set<(user: User) => void>(), expired: new Set<() => void>(), unloaded: new Set<() => void>(), signedOut: new Set<() => void>(),
}))
vi.mock('../config/runtime', async (original) => {
  const actual = await original<typeof import('../config/runtime')>()
  return { ...actual, runtimeConfig: { ...actual.runtimeConfig, authMode: 'OIDC', demoMode: false } }
})
vi.mock('./oidcCallback', async (original) => {
  const actual = await original<typeof import('./oidcCallback')>()
  return { ...actual, completeOidcRedirect: () => oidc.callback() }
})
vi.mock('oidc-client-ts', async (original) => {
  const actual = await original<typeof import('oidc-client-ts')>()
  return { ...actual, UserManager: class {
    events = {
      addUserLoaded: (fn: (user: User) => void) => oidc.loaded.add(fn), removeUserLoaded: (fn: (user: User) => void) => oidc.loaded.delete(fn),
      addAccessTokenExpired: (fn: () => void) => oidc.expired.add(fn), removeAccessTokenExpired: (fn: () => void) => oidc.expired.delete(fn),
      addUserUnloaded: (fn: () => void) => oidc.unloaded.add(fn), removeUserUnloaded: (fn: () => void) => oidc.unloaded.delete(fn),
      addUserSignedOut: (fn: () => void) => oidc.signedOut.add(fn), removeUserSignedOut: (fn: () => void) => oidc.signedOut.delete(fn),
    }
    getUser() { return oidc.getUser() }
    removeUser() { return oidc.removeUser() }
    signinRedirect() { return oidc.signinRedirect() }
    signoutRedirect() { return oidc.signoutRedirect() }
    signinRedirectCallback() { return oidc.callback() }
  } }
})

function identity(overrides: Record<string, unknown> = {}, tokenRevision = 1) {
  const now = Math.floor(Date.now() / 1000)
  const payload = { sub: 'actor-a', tenant_id: 'tenant-a', org_ids: ['org-a', 'org-b'], shop_ids: ['shop-a'], permissions: ['campaign:read', 'campaign:write'], tokenRevision, ...overrides }
  return new User({ access_token: `test.${btoa(JSON.stringify(payload))}.test`, token_type: 'Bearer', expires_at: now + 3600,
    profile: { iss: 'test-issuer', aud: 'test-console', iat: now, exp: now + 3600, sub: String(payload.sub) } })
}
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (reason: Error) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}
function Probe({ load }: { load: (scope: string) => Promise<string> }) {
  const auth = useAuth(), location = useLocation(), [draft, setDraft] = useState('')
  const scope = [auth.subject, auth.tenantId, auth.organizations.join(','), auth.shops.join(','), auth.permissions.join(',')].join('|')
  const query = useQuery({ queryKey: ['campaigns'], queryFn: () => load(scope), enabled: auth.authenticated })
  return <><span>{auth.authenticated ? '已登录' : '已退出'}</span><output data-testid="identity">{scope}</output><output data-testid="path">{location.pathname}</output><p>{query.data}</p><p>{auth.error}</p>
    <input aria-label="草稿" value={draft} onChange={event => setDraft(event.target.value)} /><button onClick={() => void auth.logout()}>退出</button><button onClick={() => void auth.completeLogin().then(oidc.completed)}>完成回调</button></>
}
function setup(load = vi.fn(async (scope: string) => `record:${scope}`), strict = false) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  const app = <QueryClientProvider client={client}><MemoryRouter><AuthProvider><Probe load={load} /></AuthProvider></MemoryRouter></QueryClientProvider>
  render(strict ? <StrictMode>{app}</StrictMode> : app)
  return { client, load }
}
async function emit(user: User) { await act(async () => { for (const callback of oidc.loaded) callback(user) }) }
async function signedIn() { await screen.findByText('已登录') }

describe('OIDC session isolation', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    oidc.loaded.clear(); oidc.expired.clear(); oidc.unloaded.clear(); oidc.signedOut.clear()
    oidc.getUser.mockResolvedValue(identity()); oidc.removeUser.mockResolvedValue(undefined); oidc.signoutRedirect.mockResolvedValue(undefined)
  })
  afterEach(() => vi.unstubAllGlobals())

  it.each([
    { sub: 'actor-b' }, { tenant_id: 'tenant-b' }, { org_ids: ['org-c'] }, { shop_ids: ['shop-b'] }, { permissions: ['campaign:read'] },
  ])('isolates cached records and drafts when claims change: %j', async (claims) => {
    const { client, load } = setup(); await signedIn(); await waitFor(() => expect(load).toHaveBeenCalledTimes(1))
    const oldRecord = client.getQueryData(['campaigns'])
    await userEvent.type(screen.getByLabelText('草稿'), 'private-draft')
    await emit(identity(claims)); await waitFor(() => expect(load).toHaveBeenCalledTimes(2))
    expect(screen.getByLabelText('草稿')).toHaveValue('')
    expect(screen.queryByText(String(oldRecord))).not.toBeInTheDocument()
    expect(client.getQueryData(['campaigns'])).not.toBe(oldRecord)
  })

  it('keeps drafts and data for same-scope token refresh and equivalent claim ordering', async () => {
    const { client, load } = setup(); await signedIn(); const old = client.getQueryData(['campaigns'])
    await userEvent.type(screen.getByLabelText('草稿'), 'keep-draft')
    await emit(identity({ org_ids: ['org-b', 'org-a', 'org-a'], permissions: ['campaign:write', 'campaign:read'] }, 2))
    expect(screen.getByLabelText('草稿')).toHaveValue('keep-draft'); expect(client.getQueryData(['campaigns'])).toBe(old); expect(load).toHaveBeenCalledTimes(1)
  })

  it.each(['success', 'error'])('does not let a late initial storage %s override a newer userLoaded', async outcome => {
    const old = deferred<User>(); oidc.getUser.mockReturnValue(old.promise); setup()
    await emit(identity({ tenant_id: 'tenant-new' })); await signedIn()
    await act(async () => { if (outcome === 'success') old.resolve(identity()); else old.reject(new Error('stale-storage-error')) })
    expect(screen.getByTestId('identity')).toHaveTextContent('tenant-new'); expect(screen.queryByText('stale-storage-error')).not.toBeInTheDocument()
  })

  it.each(['success', 'error'])('cancels the old query even when its transport finishes with %s', async outcome => {
    const old = deferred<string>(); const load = vi.fn().mockReturnValueOnce(old.promise).mockResolvedValue('new-record')
    const { client } = setup(load); await signedIn(); await emit(identity({ tenant_id: 'tenant-new' })); await screen.findByText('new-record')
    await act(async () => { if (outcome === 'success') old.resolve('private-old-record'); else old.reject(new Error('private-old-error')) })
    expect(client.getQueryData(['campaigns'])).toBe('new-record'); expect(screen.queryByText(/private-old/)).not.toBeInTheDocument()
  })

  it.each(['expired', 'unloaded', 'signedOut'] as const)('clears identity, records and drafts on %s', async event => {
    const { client } = setup(); await signedIn(); await userEvent.type(screen.getByLabelText('草稿'), 'private-draft')
    await act(async () => { for (const callback of oidc[event]) callback() })
    expect(screen.getByText('已退出')).toBeInTheDocument(); expect(screen.getByLabelText('草稿')).toHaveValue(''); expect(client.getQueryData(['campaigns'])).toBeUndefined()
  })

  it('clears locally before signout redirects and stays signed out when the IdP fails', async () => {
    const pending = deferred<void>(); oidc.signoutRedirect.mockReturnValue(pending.promise)
    const { client } = setup(); await signedIn(); await userEvent.type(screen.getByLabelText('草稿'), 'private-draft'); await userEvent.click(screen.getByText('退出'))
    expect(screen.getByRole('status')).toHaveTextContent('正在切换会话'); expect(client.getQueryData(['campaigns'])).toBeUndefined(); expect(screen.queryByLabelText('草稿')).not.toBeInTheDocument()
    await emit(identity()); expect(screen.queryByText('已登录')).not.toBeInTheDocument()
    await act(async () => pending.reject(new Error('IdP unavailable')))
    expect(screen.getByTestId('path')).toHaveTextContent('/login'); expect(screen.getByText(/已退出本机会话，但统一身份服务退出未完成/)).toBeInTheDocument()
  })

  it('rejects a late mutation response after a scope switch using the real API client', async () => {
    const pending = deferred<Response>(); vi.stubGlobal('fetch', vi.fn().mockReturnValue(pending.promise)); setup(); await signedIn()
    const response = api.createCampaign({ name: 'test', objective: 'test', organizationId: 'org-a', shopId: 'shop-a', campaignType: 'STANDARD' }).catch(error => error)
    await emit(identity({ tenant_id: 'tenant-new' })); pending.resolve(new Response(JSON.stringify({ id: 'old-id', name: 'test' }), { status: 200 }))
    const error = await response; expect(error).toBeInstanceOf(ApiProblem); expect(error.code).toBe('SESSION_CHANGED')
  })

  it('ignores a stale-token 401 after same-scope refresh, but sends the new bearer token', async () => {
    const pending = deferred<Response>(); const fetcher = vi.fn().mockReturnValueOnce(pending.promise).mockResolvedValue(new Response('[]', { status: 200 })); vi.stubGlobal('fetch', fetcher)
    setup(); await signedIn(); const response = api.campaigns().catch(error => error); const next = identity({}, 2); await emit(next)
    pending.resolve(new Response(JSON.stringify({ type: 'about:blank', title: 'Expired', status: 401, detail: 'old token' }), { status: 401 })); await response
    expect(screen.getByText('已登录')).toBeInTheDocument(); expect(oidc.removeUser).not.toHaveBeenCalled()
    await api.campaigns(); expect(new Headers(fetcher.mock.calls[1][1].headers).get('Authorization')).toBe(`Bearer ${next.access_token}`)
  })

  it('clears the current session on a current-token 401', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ type: 'about:blank', title: 'Expired', status: 401 }), { status: 401 })))
    const { client } = setup(); await signedIn(); await userEvent.type(screen.getByLabelText('草稿'), 'private-draft')
    await act(async () => { await api.campaigns().catch(() => undefined) })
    expect(screen.getByText('已退出')).toBeInTheDocument(); expect(client.getQueryData(['campaigns'])).toBeUndefined(); expect(screen.getByLabelText('草稿')).toHaveValue(''); expect(oidc.removeUser).toHaveBeenCalledTimes(1)
  })

  it('does not let a late sign-in callback replace a newer identity event', async () => {
    const pending = deferred<User>(); oidc.callback.mockReturnValue(pending.promise); setup(); await signedIn()
    await userEvent.click(screen.getByText('完成回调')); await emit(identity({ tenant_id: 'tenant-new' }))
    await act(async () => pending.resolve(identity()))
    expect(screen.getByTestId('identity')).toHaveTextContent('tenant-new'); expect(oidc.completed).toHaveBeenCalledWith('/')
  })

  it('does not restore a pending sign-in callback after logout', async () => {
    const pending = deferred<User>(); oidc.callback.mockReturnValue(pending.promise); setup(); await signedIn()
    await userEvent.click(screen.getByText('完成回调')); await userEvent.click(screen.getByText('退出'))
    await act(async () => pending.resolve(identity()))
    expect(screen.getByText('已退出')).toBeInTheDocument(); expect(oidc.completed).toHaveBeenCalledWith('/login')
  })

  it('retains one set of identity listeners in React StrictMode', async () => {
    setup(undefined, true); await signedIn(); expect(oidc.loaded.size).toBe(1); expect(oidc.expired.size).toBe(1)
    await emit(identity({ tenant_id: 'tenant-new' })); expect(screen.getByTestId('identity')).toHaveTextContent('tenant-new')
  })
})
