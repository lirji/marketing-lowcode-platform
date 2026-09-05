import { useEffect, useMemo, useState } from 'react'
import { Bell, ChevronDown, Command, Menu, Search, X } from 'lucide-react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { navigation } from './navigation'
import { useAuth } from '../shared/auth/useAuth'
import { useQuery } from '@tanstack/react-query'
import { api } from '../shared/api/client'
import { ErrorBoundary } from '../components/ErrorBoundary'
import { Modal } from '../components/ui'

declare global {
  interface Document {
    modelContext?: {
      registerTool(tool: Record<string, unknown>, options?: { signal?: AbortSignal }): void | Promise<void>
    }
  }
}

export function AppShell() {
  const [navOpen, setNavOpen] = useState(false)
  const [searchOpen, setSearchOpen] = useState(false)
  const [logoutOpen, setLogoutOpen] = useState(false)
  const [query, setQuery] = useState('')
  const navigate = useNavigate()
  const location = useLocation()
  const auth = useAuth()
  const permissions = auth.permissions
  const authenticated = auth.authenticated
  const visibleNav = useMemo(
    () => navigation
      .map((group) => ({ ...group, items: group.items.filter((item) => auth.hasAnyPermission(item.permissions)) }))
      .filter((group) => group.items.length > 0),
    [permissions, authenticated, auth.hasAnyPermission],
  )
  const searchable = useMemo(() => visibleNav.flatMap((group) => group.items), [visibleNav])
  const results = searchable.filter((item) => item.label.toLowerCase().includes(query.toLowerCase()))
  const approvals = useQuery({
    queryKey: ['approvals', 'badge'],
    queryFn: api.approvals,
    enabled: authenticated && !api.demoMode && auth.hasAnyPermission(['approval:business', 'approval:finance', 'approval:compliance', 'approval:merchant', 'definition:read']),
  })
  const releases = useQuery({
    queryKey: ['releases', 'health'],
    queryFn: api.releases,
    enabled: authenticated && !api.demoMode && auth.hasPermission('release:read'),
  })
  const openApprovals = (approvals.data ?? []).filter((item) => item.status === 'OPEN').length
  const activeCells = (releases.data ?? []).filter((item) => item.state === 'ACTIVE').length
  const orgLabel = auth.organizations.includes('*') || auth.organizations.length === 0
    ? '全域组织'
    : auth.organizations.join(' / ')

  useEffect(() => setNavOpen(false), [location.pathname])
  useEffect(() => {
    const handleKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        setSearchOpen(true)
      }
      if (event.key === 'Escape') setSearchOpen(false)
    }
    window.addEventListener('keydown', handleKey)
    return () => window.removeEventListener('keydown', handleKey)
  }, [])

  useEffect(() => {
    const context = document.modelContext
    if (!context?.registerTool) return
    const lifecycle = new AbortController()
    const tools = [
      {
        name: 'navigate_marketing_console',
        title: '打开营销工作台页面',
        description: '导航到指定营销工作台模块，不修改业务数据。',
        inputSchema: { type: 'object', properties: { path: { type: 'string', enum: searchable.map((item) => item.path) } }, required: ['path'], additionalProperties: false },
        annotations: { readOnlyHint: true, untrustedContentHint: false },
        execute: (input: unknown) => {
          const path = typeof input === 'object' && input !== null && 'path' in input ? String(input.path) : ''
          if (!searchable.some((item) => item.path === path)) throw new Error('不支持的工作台路径')
          navigate(path)
          return { path, status: 'opened' }
        },
      },
    ]
    for (const tool of tools) {
      try { void Promise.resolve(context.registerTool(tool, { signal: lifecycle.signal })).catch(() => undefined) }
      catch { /* Optional browser capability. */ }
    }
    return () => lifecycle.abort()
  }, [navigate, searchable])

  return (
    <div className="app-shell">
      <aside className={`sidebar ${navOpen ? 'open' : ''}`}>
        <div className="brand"><span className="brand-mark">M</span><span><strong>Meridian</strong><small>营销中枢</small></span><button className="mobile-close" type="button" onClick={() => setNavOpen(false)} aria-label="关闭菜单"><X size={17} /></button></div>
        <nav aria-label="主导航">
          {visibleNav.map((group) => (
            <div className="nav-group" key={group.label}>
              <p className="nav-label">{group.label}</p>
              {group.items.map(({ label, path, icon: Icon }) => (
                <NavLink end={path === '/'} className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`} to={path} key={path}>
                  <Icon size={17} aria-hidden="true" /><span>{label}</span>
                  {path === '/governance' && openApprovals > 0 && <b className="nav-badge">{openApprovals}</b>}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>
        <div className="sidebar-foot">
          <div className={`health-orb ${api.demoMode || activeCells > 0 ? 'ok' : 'unknown'}`} />
          <div>
            <strong>{auth.tenantId || '未声明租户'}</strong>
            <small>{api.demoMode ? '演示模式 · 非生产数据' : activeCells > 0 ? `${activeCells} 个 Active 发布槽` : '等待运行时就绪'}</small>
          </div>
        </div>
      </aside>
      {navOpen && <button className="nav-backdrop" type="button" aria-label="关闭菜单" onClick={() => setNavOpen(false)} />}
      <main>
        <header className="topbar">
          <button className="mobile-menu" type="button" aria-label="打开菜单" onClick={() => setNavOpen(true)}><Menu size={19} /></button>
          <div className="tenant-switch" title="租户与组织范围来自身份令牌，切换需重新登录">
            {orgLabel} · {auth.shops.includes('*') || auth.shops.length === 0 ? '全部店铺' : auth.shops.join(' / ')}
          </div>
          <button className="global-search" type="button" onClick={() => setSearchOpen(true)}><Search size={16} /><span>搜索模块…</span><kbd><Command size={11} /> K</kbd></button>
          <button className="icon-button" type="button" aria-label={openApprovals > 0 ? `${openApprovals} 条待审核` : '通知'} onClick={() => navigate('/governance')}>
            <Bell size={18} />
            {openApprovals > 0 && <i />}
          </button>
          <button className="profile" type="button" onClick={() => setLogoutOpen(true)} title="账户">
            <span>{auth.displayName.slice(0, 1)}</span>
            <div><strong>{auth.displayName}</strong><small>{auth.subject || '未登录'}</small></div>
            <ChevronDown size={14} />
          </button>
        </header>
        <ErrorBoundary tenantId={auth.tenantId} actorId={auth.subject}>
          <Outlet />
        </ErrorBoundary>
      </main>
      {searchOpen && (
        <div className="modal-layer" role="dialog" aria-modal="true" aria-labelledby="command-title" onMouseDown={() => setSearchOpen(false)}>
          <section className="command-panel" onMouseDown={(event) => event.stopPropagation()}>
            <div className="command-input"><Search size={18} /><h2 id="command-title" className="sr-only">全局搜索</h2><input autoFocus value={query} onChange={(event) => setQuery(event.target.value)} placeholder="输入模块名称…" /><kbd>ESC</kbd></div>
            <div className="command-results">{results.map(({ label, path, icon: Icon }) => <button key={path} type="button" onClick={() => { navigate(path); setSearchOpen(false) }}><Icon size={16} /><span>{label}</span><small>{path}</small></button>)}</div>
          </section>
        </div>
      )}
      {logoutOpen && (
        <Modal title="退出登录" description="将结束当前运营会话并返回身份提供方。" onClose={() => setLogoutOpen(false)}>
          <div className="danger-confirm">
            <p>确认退出「{auth.displayName}」吗？未保存的设计器草稿会留在浏览器本地。</p>
            <footer className="modal-actions">
              <button className="button secondary" type="button" onClick={() => setLogoutOpen(false)}>取消</button>
              <button className="button danger" type="button" onClick={() => void auth.logout()}>退出</button>
            </footer>
          </div>
        </Modal>
      )}
    </div>
  )
}
