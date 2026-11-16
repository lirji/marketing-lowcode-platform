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
