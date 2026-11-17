import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, invalidateApiSession, setUnauthorizedHandler } from './client'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

describe('API session fencing', () => {
  afterEach(() => { vi.unstubAllGlobals(); setUnauthorizedHandler(() => undefined) })

  it('does not deliver an old write response to a mutation success callback', async () => {
    const response = deferred<Response>()
    vi.stubGlobal('fetch', vi.fn(() => response.promise))
    const success = vi.fn()
    const pending = api.createCampaign({ name: 'old campaign', objective: 'old objective', organizationId: 'org-a', shopId: '', campaignType: 'STANDARD' }).then(success)
    const rejection = expect(pending).rejects.toMatchObject({ code: 'SESSION_CHANGED', retryable: false })
    invalidateApiSession()
    response.resolve(new Response(JSON.stringify({ id: 'old-id', name: 'old campaign', objective: 'old objective', status: 'DRAFT', createdAt: '2026-11-16T00:00:00Z' })))
    await rejection
    expect(success).not.toHaveBeenCalled()
  })

  it('rejects a body that finishes after switching and never signs out the new session for an old 401', async () => {
    const body = deferred<unknown>()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, json: () => body.promise }))
    const pending = api.campaigns()
    await Promise.resolve()
    const rejection = expect(pending).rejects.toMatchObject({ code: 'SESSION_CHANGED' })
    invalidateApiSession()
    body.resolve([])
    await rejection

    const response = deferred<Response>()
    vi.stubGlobal('fetch', vi.fn(() => response.promise))
    const unauthorized = vi.fn()
    setUnauthorizedHandler(unauthorized)
    const old = api.campaigns()
    const oldRejection = expect(old).rejects.toMatchObject({ code: 'SESSION_CHANGED' })
    invalidateApiSession()
    response.resolve(new Response('{}', { status: 401 }))
    await oldRejection
    expect(unauthorized).not.toHaveBeenCalled()
  })
})


describe('automatic decision trace fallback', () => {
  afterEach(() => vi.unstubAllGlobals())
  it('reads an automatic snapshot only after an explicit missing aggregate trace', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ type: 'about:blank', title: '不存在', status: 404 }), { status: 404 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ traceId: 'quote-1', requestId: 'request-1', events: [] })))
    vi.stubGlobal('fetch', fetch)
    expect((await api.traceByRequest('request-1')).traceId).toBe('quote-1')
    expect(fetch.mock.calls[1][0]).toContain('/api/v1/decisions/traces/requests/request-1')
  })
  it('rejects a session switch between the missing response and fallback request', async () => {
    const fetch = vi.fn().mockResolvedValueOnce({ ok: false, status: 404,
      json: () => Promise.resolve({ type: 'about:blank', title: '不存在', detail: '无轨迹', status: 404 }).then((value) => {
        // request 的最后一次检查之后、外层补查 catch 之前切换会话。
        queueMicrotask(() => queueMicrotask(() => invalidateApiSession()))
        return value
      }),
    }).mockResolvedValueOnce(new Response(JSON.stringify({ traceId: 'new-tenant-trace', events: [] })))
    vi.stubGlobal('fetch', fetch)
    await expect(api.traceByRequest('old-tenant-request')).rejects.toMatchObject({ code: 'SESSION_CHANGED' })
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('preserves permission and dependency failures without querying another endpoint', async () => {
    for (const status of [401, 403, 502, 503]) {
      const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ type: 'about:blank', title: '失败', status }), { status }))
      vi.stubGlobal('fetch', fetch)
      await expect(api.traceByOrder('order-1')).rejects.toMatchObject({ status })
      expect(fetch.mock.calls.filter(([url]) => String(url).includes('/api/v1/traces/'))).toHaveLength(1)
      expect(fetch.mock.calls.some(([url]) => String(url).includes('/api/v1/decisions/traces/'))).toBe(false)
    }
  })
})

describe('release page contract', () => {
  afterEach(() => vi.unstubAllGlobals())
  const item = { manifest: { manifestId: 'manifest-old', cell: 'cell-a', generation: 1, artifacts: [] }, state: 'ACTIVE' }
  it('rejects a mismatched state instead of showing a different result set', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ items: [item], state: null, nextCursor: null, hasMore: false }))))
    await expect(api.releasePage({ state: 'ACTIVE' })).rejects.toThrow('发布服务未确认当前筛选')
  })
  it('rejects a repeated cursor instead of offering an endless load-more loop', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ items: [item], state: null, nextCursor: 'manifest-old', hasMore: true }))))
    await expect(api.releasePage({ cursor: 'manifest-old' })).rejects.toThrow('重复游标')
  })
})


describe('single release contract', () => {
  afterEach(() => vi.unstubAllGlobals())
  it('reads the encoded ID and rejects a response for a different manifest', async () => {
    const item = { manifest: { manifestId: 'manifest-old', cell: 'cell-a', generation: 1, artifacts: [] }, state: 'STAGED' }
    const fetch = vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify(item))))
    vi.stubGlobal('fetch', fetch)
    await expect(api.getRelease('manifest-old')).resolves.toMatchObject(item)
    expect(fetch.mock.calls[0][0]).toContain('/api/v1/releases/manifest-old')
    await expect(api.getRelease('another manifest')).rejects.toThrow('不同的清单')
    expect(fetch.mock.calls[1][0]).toContain('/api/v1/releases/another%20manifest')
  })
})


describe('audience page contract', () => {
  const item = { segmentId: 'segment-a', version: 1, name: '人群', status: 'ACTIVE' }
  afterEach(() => vi.unstubAllGlobals())
  it('encodes literal search and rejects a mismatched search or repeated cursor', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ items: [item], nextCursor: null, hasMore: false, query: '100%_ 人群' })))
    vi.stubGlobal('fetch', fetcher)
    await api.audiencePage({ q: ' 100%_ 人群 ', limit: 25 })
    expect(new URL(String(fetcher.mock.calls[0][0]), 'http://local').searchParams.get('q')).toBe('100%_ 人群')
    fetcher.mockResolvedValueOnce(new Response(JSON.stringify({ items: [item], nextCursor: null, hasMore: false, query: '' })))
    await expect(api.audiencePage({ q: 'needle' })).rejects.toThrow('人群服务未确认当前搜索')
    fetcher.mockResolvedValueOnce(new Response(JSON.stringify({ items: [item], nextCursor: 'segment-a', hasMore: true, query: '' })))
    await expect(api.audiencePage({ cursor: 'segment-a' })).rejects.toThrow('重复游标')
  })
  it('rejects missing pagination metadata and duplicate segment IDs', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ items: [item] })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ items: [item, item], nextCursor: null, hasMore: false, query: '' })))
    vi.stubGlobal('fetch', fetcher)
    await expect(api.audiencePage()).rejects.toThrow()
    await expect(api.audiencePage()).rejects.toThrow()
  })
})
