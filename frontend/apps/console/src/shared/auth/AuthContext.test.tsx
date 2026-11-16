import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from './AuthContext'
import { setDevTenantId } from './devTenant'
import { useAuth } from './useAuth'

function SessionProbe({ load }: { load: (tenant: string) => Promise<string> }) {
  const auth = useAuth()
  const location = useLocation()
  const [draft, setDraft] = useState('')
  const query = useQuery({ queryKey: ['campaigns'], queryFn: () => load(auth.tenantId), enabled: auth.authenticated && location.pathname !== '/login' })
  return <>
    <span>{auth.tenantId}</span>
    <span>{auth.authenticated ? '已登录' : '已退出'}</span>
    <span>{query.data}</span>
    <input aria-label="草稿" value={draft} onChange={(event) => setDraft(event.target.value)} />
    <button onClick={() => { setDevTenantId('tenant-b'); void auth.login('/') }}>切换租户</button>
    <button onClick={() => void auth.logout()}>退出</button>
  </>
}

function setup(load: (tenant: string) => Promise<string>) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 60_000 } } })
  render(<QueryClientProvider client={client}><MemoryRouter><AuthProvider><SessionProbe load={load} /></AuthProvider></MemoryRouter></QueryClientProvider>)
  return client
}

describe('DEV session isolation', () => {
  beforeEach(() => setDevTenantId('tenant-a'))

  it('switches a fresh cache and resets local drafts', async () => {
    const user = userEvent.setup()
    const load = vi.fn(async (tenant: string) => `${tenant}-campaign`)
    const client = setup(load)
    expect(await screen.findByText('tenant-a-campaign')).toBeInTheDocument()
    await user.type(screen.getByLabelText('草稿'), 'tenant-a draft')
    await user.click(screen.getByText('切换租户'))
    expect(await screen.findByText('tenant-b-campaign')).toBeInTheDocument()
    expect(screen.queryByText('tenant-a-campaign')).not.toBeInTheDocument()
    expect(screen.getByLabelText('草稿')).toHaveValue('')
    expect(client.getQueryData(['campaigns'])).toBe('tenant-b-campaign')
    expect(load).toHaveBeenCalledWith('tenant-b')
  })

  it.each(['success', 'error'])('ignores a late old-tenant %s after switching', async (outcome) => {
    let resolve!: (value: string) => void
    let reject!: (reason: Error) => void
    const pending = new Promise<string>((done, fail) => { resolve = done; reject = fail })
    const load = vi.fn((tenant: string) => tenant === 'tenant-a' ? pending : Promise.resolve('tenant-b-campaign'))
    const client = setup(load)
    await waitFor(() => expect(load).toHaveBeenCalledWith('tenant-a'))
    await userEvent.click(screen.getByText('切换租户'))
    expect(await screen.findByText('tenant-b-campaign')).toBeInTheDocument()
    await act(async () => { if (outcome === 'success') resolve('tenant-a-campaign'); else reject(new Error('old error')) })
    expect(client.getQueryData(['campaigns'])).toBe('tenant-b-campaign')
    expect(client.getQueryState(['campaigns'])?.status).toBe('success')
    expect(screen.queryByText('tenant-a-campaign')).not.toBeInTheDocument()
  })

  it('clears cached records and local state on logout', async () => {
    const client = setup(async () => 'private-campaign')
    expect(await screen.findByText('private-campaign')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('草稿'), 'private draft')
    await userEvent.click(screen.getByText('退出'))
    expect(await screen.findByText('已退出')).toBeInTheDocument()
    expect(screen.queryByText('private-campaign')).not.toBeInTheDocument()
    expect(client.getQueryData(['campaigns'])).toBeUndefined()
    expect(screen.getByLabelText('草稿')).toHaveValue('')
  })
})
