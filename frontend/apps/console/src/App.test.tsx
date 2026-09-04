import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import App from './App'
import { AuthProvider } from './shared/auth/AuthContext'
import { permits } from './shared/auth/auth-context'
import { decodeJwtPayload, splitClaim } from './shared/auth/claims'

function renderApp(path = '/') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <AuthProvider>
          <App />
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('marketing console routing and core interactions', () => {
  it('renders the operational dashboard and its governed release state', async () => {
    renderApp()
    expect(await screen.findByRole('heading', { name: '早上好，今天的增长脉搏稳定。' })).toBeInTheDocument()
    expect(screen.getByText('发布代际 #1842')).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: '主导航' })).toBeInTheDocument()
    expect(screen.getByText('演示数据')).toBeInTheDocument()
  })

  it('opens campaign creation from a deep link and enforces schema validation', async () => {
    const user = userEvent.setup()
    renderApp('/campaigns?create=true')
    expect(await screen.findByRole('dialog', { name: '创建营销活动' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '创建并进入设计' }))
    expect(await screen.findByText('活动名称至少 2 个字')).toBeInTheDocument()
    expect(screen.getByText('请描述可衡量的业务目标')).toBeInTheDocument()
  })

  it('navigates to the release control center without a document reload', async () => {
    const user = userEvent.setup()
    renderApp()
    await user.click(await screen.findByRole('link', { name: '查看发布态势' }))
    expect(await screen.findByRole('heading', { name: '发布中心' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /推进灰度/ })).toBeDisabled()
  })
})

describe('identity helpers', () => {
  it('treats wildcard as all permissions and parses JWT claims', () => {
    expect(permits(['*'], 'release:kill-switch')).toBe(true)
    expect(permits(['campaign:read'], 'release:kill-switch')).toBe(false)
    expect(splitClaim('business, finance')).toEqual(['business', 'finance'])
    const payload = decodeJwtPayload(`header.${btoa(JSON.stringify({ tenant_id: 'retail-cn', permissions: 'campaign:read' }))}.sig`)
    expect(payload.tenant_id).toBe('retail-cn')
  })
})
