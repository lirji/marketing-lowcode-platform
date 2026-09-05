import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from '../shared/auth/useAuth'
import { sanitizeReturnTo } from '../shared/auth/tenantSelection'
import type { ReactNode } from 'react'

export function RequireAuth({ children }: { children: ReactNode }) {
  const auth = useAuth()
  const location = useLocation()
  if (!auth.ready) return <div className="route-loading" role="status">正在验证运营身份…</div>
  if (!auth.authenticated) {
    const returnTo = sanitizeReturnTo(`${location.pathname}${location.search}`)
    return <Navigate to={`/login?returnTo=${encodeURIComponent(returnTo)}`} replace />
  }
  return children
}
