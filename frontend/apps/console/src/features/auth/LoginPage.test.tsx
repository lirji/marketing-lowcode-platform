import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { AuthProvider } from '../../shared/auth/AuthContext'
import { LoginPage } from './LoginPage'

function renderLogin(path = '/login') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <LoginPage />
      </AuthProvider>
    </MemoryRouter>,
  )
}

describe('LoginPage', () => {
  it('shows the dual-column brand login and accepts the default dev tenant', async () => {
    const user = userEvent.setup()
    renderLogin('/login?returnTo=%2Freleases')
    expect(screen.getByRole('heading', { name: '进入本地开发模式' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'retail-cn' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '进入本地开发模式' }))
    expect(window.sessionStorage.getItem('marketing.dev.tenantId')).toBe('retail-cn')
  })

  it('keeps unknown organizations from submitting in the form copy', async () => {
    renderLogin()
    expect(screen.getByLabelText('货主业务租户')).toHaveValue('retail-cn')
    expect(screen.getByText(/权益 DEV 默认是 dev-tenant/)).toBeInTheDocument()
    expect(screen.getByText(/本地开发模式免登录/)).toBeInTheDocument()
  })
})
