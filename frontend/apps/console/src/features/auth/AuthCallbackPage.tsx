import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { runtimeConfig } from '../../shared/config/runtime'
import { useAuth } from '../../shared/auth/useAuth'

export function AuthCallbackPage() {
  const auth = useAuth()
  const navigate = useNavigate()
  const completeLogin = useRef(auth.completeLogin)
  completeLogin.current = auth.completeLogin
  const [error, setError] = useState('')

  useEffect(() => {
    if (runtimeConfig.authMode !== 'OIDC') {
      navigate('/', { replace: true })
      return
    }
    let cancelled = false
    void completeLogin.current().then(
      (returnTo) => {
        if (!cancelled) navigate(returnTo, { replace: true })
      },
      (cause) => {
        if (cancelled) return
        setError(cause instanceof Error ? cause.message : 'OIDC 登录失败')
      },
    )
    return () => {
      cancelled = true
    }
  }, [navigate])

  if (error) {
    return (
      <div className="auth-error" role="alert">
        <h1>无法完成登录</h1>
        <p>{error}</p>
        <Link className="button primary" to="/login">返回登录</Link>
      </div>
    )
  }

  return <div className="route-loading" role="status">正在完成登录…</div>
}
