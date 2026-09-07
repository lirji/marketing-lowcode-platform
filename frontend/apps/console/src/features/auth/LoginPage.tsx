import { useState, type FormEvent } from 'react'
import { Navigate, useSearchParams } from 'react-router-dom'
import {
  ArrowRight,
  Building2,
  CheckCircle2,
  GitBranch,
  Layers,
  LogIn,
  Megaphone,
  ShieldCheck,
} from 'lucide-react'
import { runtimeConfig } from '../../shared/config/runtime'
import { useAuth } from '../../shared/auth/useAuth'
import { DEFAULT_DEV_TENANT, getDevTenantId, setDevTenantId } from '../../shared/auth/devTenant'
import { sanitizeReturnTo, validateTenantSelection } from '../../shared/auth/tenantSelection'
import './login.css'

const FEATURES = [
  { icon: Layers, title: '低代码画布', desc: 'Offer、Audience、Journey 与 DMN 在同一工作台治理' },
  { icon: ShieldCheck, title: '规则与定价', desc: '不可变规则制品、分层定价与精确分摊进入运行面' },
  { icon: GitBranch, title: '受众与旅程', desc: '实时 membership、频控同意和有状态旅程编排' },
  { icon: Megaphone, title: '发布回滚', desc: '灰度 ACK、kill switch 与四眼审核可审计回放' },
]

export function LoginPage() {
  const auth = useAuth()
  const [params] = useSearchParams()
  const isOidc = runtimeConfig.authMode === 'OIDC'
  const [tenant, setTenant] = useState(isOidc ? runtimeConfig.oidcOrganization : getDevTenantId())
  const [tenantError, setTenantError] = useState('')
  const returnTo = sanitizeReturnTo(params.get('returnTo'))

  if (auth.ready && auth.authenticated && isOidc) {
    return <Navigate to={returnTo} replace />
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setTenantError('')
    if (isOidc) {
      const result = validateTenantSelection(tenant, runtimeConfig.oidcOrganization, runtimeConfig.oidcClientId)
      if (!result.ok) {
        setTenantError(result.message)
        return
      }
    } else {
      setDevTenantId(tenant || DEFAULT_DEV_TENANT)
    }
    await auth.login(returnTo)
  }

  const suggested = isOidc ? runtimeConfig.oidcOrganization : DEFAULT_DEV_TENANT

  return (
    <main className="login-root">
      <div className="login-card">
        <aside className="login-aside">
          <span className="login-aside-ring" aria-hidden />
          <div className="login-aside-top">
            <span className="login-mark" aria-hidden>M</span>
            <span className="login-aside-name">营销低代码平台</span>
          </div>
          <div className="login-aside-main">
            <p className="login-aside-kicker">MERIDIAN</p>
            <h1 className="login-aside-title">
              活动可治理
              <br />
              发布可回滚
            </h1>
            <p className="login-aside-sub">运营先设计规则与受众，再经审核进入运行面。资金不变量不在画布上口头承诺。</p>
            <ul className="login-features">
              {FEATURES.map((feature) => (
                <li key={feature.title}>
                  <span className="login-feature-icon"><feature.icon size={17} aria-hidden /></span>
                  <span className="login-feature-text">
                    <b>{feature.title}</b>
                    <small>{feature.desc}</small>
                  </span>
                </li>
              ))}
            </ul>
          </div>
          <div className="login-aside-foot">Marketing Low-code · Operations Console</div>
        </aside>

        <section className="login-form">
          <div className="login-form-brand">
            <span className="login-mark" aria-hidden>M</span>
            <span>营销低代码平台</span>
          </div>
          <p className="login-kicker">{isOidc ? 'Casdoor SSO · PKCE' : 'Local · Dev Mode'}</p>
          <h2 className="login-form-title">{isOidc ? '欢迎进入营销中枢' : '进入本地开发模式'}</h2>
          <p className="login-form-sub">
            {isOidc
              ? '这里填登录组织 marketing-platform，不是货主。货主来自 JWT 的 tenant_id。'
              : '这里填货主业务租户，写入 X-Dev-Tenant-Id。必须与权益中台建商品时相同，目录才会有货。权益 DEV 默认是 dev-tenant；填不一样则列表为空，不要去换登录组织。'}
          </p>

          <form onSubmit={(event) => void submit(event)}>
            <label className="login-field">
              <span>{isOidc ? '登录组织' : '货主业务租户'}</span>
              <span className={`login-input ${tenantError ? 'is-invalid' : ''}`}>
                <Building2 size={16} aria-hidden />
                <input
                  value={tenant}
                  onChange={(event) => {
                    setTenant(event.target.value)
                    setTenantError('')
                  }}
                  disabled={auth.redirecting}
                  autoComplete="organization"
                  spellCheck={false}
                  aria-label={isOidc ? '登录组织' : '货主业务租户'}
                  placeholder={isOidc ? '例如 marketing-platform' : DEFAULT_DEV_TENANT}
                />
              </span>
              {tenantError ? <small className="login-field-error">{tenantError}</small> : null}
            </label>

            {suggested ? (
              <div className="login-tenants">
                <span className="login-tenants-label">{isOidc ? '可用组织' : '建议货主'}</span>
                <button
                  type="button"
                  className={`login-tenant-chip ${tenant.trim() === suggested ? 'is-active' : ''}`}
                  disabled={auth.redirecting}
                  onClick={() => {
                    setTenant(suggested)
                    setTenantError('')
                  }}
                >
                  {suggested}
                </button>
              </div>
            ) : null}

            {auth.error ? <p className="login-alert" role="alert">{auth.error}</p> : null}

            <button className="login-submit" type="submit" disabled={auth.redirecting}>
              <span className="login-submit-main">
                {!auth.redirecting && <LogIn size={16} aria-hidden />}
                {auth.redirecting ? '正在跳转 Casdoor…' : isOidc ? '使用 Casdoor 登录' : '进入本地开发模式'}
              </span>
              {!auth.redirecting && isOidc ? <ArrowRight size={16} className="login-submit-arrow" aria-hidden /> : null}
            </button>
          </form>

          <div className="login-secure-note">
            <CheckCircle2 size={15} aria-hidden />
            <span>
              {isOidc
                ? '由 Casdoor 提供统一身份认证，使用 OIDC Authorization Code + PKCE。'
                : '本地开发模式免登录，请勿用于生产环境。'}
            </span>
          </div>
        </section>
      </div>
      <p className="login-foot">营销低代码平台 · 内部运营使用</p>
    </main>
  )
}
