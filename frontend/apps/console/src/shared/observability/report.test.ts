import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const originalConfig = window.__MARKETING_CONFIG__
const trace = 'abcdef0123456789abcdef0123456789'
async function setup(mode: 'DEV' | 'OIDC' = 'DEV', demo = false) {
  vi.resetModules()
  window.__MARKETING_CONFIG__ = { ...originalConfig, DEMO_MODE: demo, AUTH_MODE: mode, ALLOW_DEV_AUTH: true }
  const report = await import('./report')
  const client = await import('../api/client')
  const fetch = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
  vi.stubGlobal('fetch', fetch)
  return { ...report, ...client, fetch }
}

describe('bounded authenticated client diagnostics', () => {
  beforeEach(() => { vi.spyOn(console, 'error').mockImplementation(() => undefined) })
  afterEach(() => { window.__MARKETING_CONFIG__ = originalConfig; vi.unstubAllGlobals(); vi.restoreAllMocks(); vi.useRealTimers() })

  it('skips anonymous and cleared DEV identities', async () => {
    const s = await setup()
    s.reportUnknownError(new Error('secret'), { code: 'WINDOW_ERROR' })
    expect(s.fetch).not.toHaveBeenCalled()
    s.setIdentityProvider(() => ({ tenantId: 'retail-cn', actorId: 'console-admin' }))
    s.reportUnknownError(new Error('secret'), { code: 'WINDOW_ERROR' })
    expect(s.fetch).toHaveBeenCalledTimes(1)
    s.setIdentityProvider(() => ({}))
    s.reportUnknownError(new Error('secret'), { code: 'WINDOW_ERROR' })
    expect(s.fetch).toHaveBeenCalledTimes(1)
  })

  it('uses authenticated DEV headers but sends only fixed safe event fields', async () => {
    const s = await setup()
    s.setIdentityProvider(() => ({ tenantId: 'retail-cn', actorId: 'console-admin' }))
    const getter = vi.fn(() => { throw new Error('never inspect this secret') })
    const cause = Object.defineProperty({}, 'message', { get: getter })
    s.reportUnknownError(cause, { code: 'REACT_BOUNDARY', path: '/assets/private-member?token=secret#private' })
    const [url, request] = s.fetch.mock.calls[0]
    expect(url).toBe('/api/v1/client-errors')
    expect(request.headers).toMatchObject({ 'Content-Type': 'application/json', 'X-Dev-Tenant-Id': 'retail-cn' })
    expect(JSON.parse(request.body)).toEqual({ schemaVersion: 1, source: 'REACT', route: 'ASSETS', httpStatus: 0 })
    expect(request.keepalive).toBe(true)
    expect(getter).not.toHaveBeenCalled()
    expect(JSON.stringify(vi.mocked(console.error).mock.calls)).not.toMatch(/private|secret|retail-cn|console-admin/)
  })

  it('uses the current OIDC token and refuses missing tokens even when claims remain', async () => {
    const s = await setup('OIDC')
    s.setIdentityProvider(() => ({ tenantId: 'tenant', actorId: 'actor' }))
    s.reportClientError({ source: 'API', httpStatus: 502 })
    expect(s.fetch).not.toHaveBeenCalled()
    s.setAccessTokenProvider(() => 'first-token')
    s.reportClientError({ source: 'API', httpStatus: 502, path: '/api/v1/templates/private?q=secret', backendTraceId: trace.toUpperCase() })
    expect(s.fetch.mock.calls[0][1].headers).toEqual({ Authorization: 'Bearer first-token', 'Content-Type': 'application/json' })
    expect(JSON.parse(s.fetch.mock.calls[0][1].body)).toEqual({ schemaVersion: 1, source: 'API', route: 'ASSETS', httpStatus: 502, backendTraceId: trace })
    s.setAccessTokenProvider(() => 'second-token')
    s.reportClientError({ source: 'API', backendTraceId: 'not-a-trace-sensitive' })
    expect(s.fetch.mock.calls[1][1].headers.Authorization).toBe('Bearer second-token')
    expect(s.fetch.mock.calls[1][1].body).not.toContain('not-a-trace')
  })

  it('does not send DEMO diagnostics', async () => {
    const s = await setup('DEV', true)
    s.setIdentityProvider(() => ({ tenantId: 'retail-cn', actorId: 'actor' }))
    s.reportUnknownError('secret', { code: 'UNHANDLED_REJECTION' })
    expect(s.fetch).not.toHaveBeenCalled()
  })

  it('caps each page at ten events per minute and aborts stalled reports', async () => {
    const s = await setup()
    vi.useFakeTimers()
    s.setIdentityProvider(() => ({ tenantId: 'retail-cn', actorId: 'actor' }))
    s.fetch.mockImplementation(() => new Promise(() => undefined))
    for (let i = 0; i < 30; i++) s.reportUnknownError('secret', { code: 'UNHANDLED_REJECTION' })
    expect(s.fetch).toHaveBeenCalledTimes(10)
    expect(s.fetch.mock.calls[0][1].signal.aborted).toBe(false)
    await vi.advanceTimersByTimeAsync(3000)
    expect(s.fetch.mock.calls[0][1].signal.aborted).toBe(true)
    await vi.advanceTimersByTimeAsync(57000)
    s.reportUnknownError('secret', { code: 'UNHANDLED_REJECTION' })
    expect(s.fetch).toHaveBeenCalledTimes(11)
  })

  it('never recursively reports collector failure, rejection or synchronous fetch failure', async () => {
    const s = await setup()
    s.setIdentityProvider(() => ({ tenantId: 'retail-cn', actorId: 'actor' }))
    s.fetch.mockResolvedValueOnce(new Response('{}', { status: 503 }))
    s.reportUnknownError('secret', { code: 'WINDOW_ERROR' })
    await Promise.resolve(); await Promise.resolve()
    expect(s.fetch).toHaveBeenCalledTimes(1)
    s.fetch.mockRejectedValueOnce(new Error('network secret'))
    s.reportUnknownError('secret', { code: 'WINDOW_ERROR' })
    await Promise.resolve(); await Promise.resolve()
    expect(s.fetch).toHaveBeenCalledTimes(2)
    s.fetch.mockImplementationOnce(() => { throw new Error('sync secret') })
    expect(() => s.reportUnknownError('secret', { code: 'WINDOW_ERROR' })).not.toThrow()
    expect(s.fetch).toHaveBeenCalledTimes(3)
  })

  it('captures real API 5xx classification and trace without forwarding error details', async () => {
    const s = await setup()
    s.setIdentityProvider(() => ({ tenantId: 'retail-cn', actorId: 'actor' }))
    s.fetch.mockResolvedValueOnce(new Response(JSON.stringify({ type: 'about:blank', title: 'Failure', detail: 'Bearer private-value', status: 503, code: 'PRIVATE_SECRET', traceId: trace }), { status: 503 }))
    await expect(s.api.campaigns()).rejects.toMatchObject({ status: 503 })
    expect(s.fetch).toHaveBeenCalledTimes(2)
    expect(JSON.parse(s.fetch.mock.calls[1][1].body)).toEqual({ schemaVersion: 1, source: 'API', route: 'CAMPAIGNS', httpStatus: 503, backendTraceId: trace })
    expect(JSON.stringify(vi.mocked(console.error).mock.calls)).not.toMatch(/private-value|PRIVATE_SECRET/)
  })
})
