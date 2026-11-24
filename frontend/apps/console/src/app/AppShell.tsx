import { useEffect, useMemo, useRef, useState } from 'react'
import { Building2, ChevronDown, ClipboardCheck, Command, Menu, Search, X } from 'lucide-react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { navigation } from './navigation'
import { useAuth } from '../shared/auth/useAuth'
import { useReleasePages } from '../shared/api/useReleasePages'
import { api } from '../shared/api/client'
import { APPROVAL_PAGE_SIZES, ReleaseState } from '../shared/api/schemas'
import { APPROVAL_STATUS, useApprovalPages } from '../shared/api/useApprovalPages'
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
  const menuTrigger = useRef<HTMLButtonElement>(null)
  const navClose = useRef<HTMLButtonElement>(null)
  const searchTrigger = useRef<HTMLButtonElement>(null)
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
  const searchable = useMemo(() => visibleNav.flatMap((group) => group.items.map(item => ({ ...item, group: group.label }))), [visibleNav])
  const results = searchable.filter((item) => item.label.toLowerCase().includes(query.trim().toLowerCase()))
  const canOpenGovernance = searchable.some(item => item.path === '/governance')
  const approvals = useApprovalPages(APPROVAL_STATUS.OPEN, authenticated, APPROVAL_PAGE_SIZES.MAX)
  const releases = useReleasePages(ReleaseState.ACTIVE)
  const openApprovals = approvals.items.length
  const openApprovalLabel = `${openApprovals}${approvals.hasNextPage ? '+' : ''}`
  const activeReleases = releases.items.length
  const releaseSummary = !releases.canRead ? '无发布读取权限'
    : releases.isError ? '发布状态读取失败'
      : releases.isPending ? '发布状态加载中'
        : `已激活清单 ${activeReleases}${releases.hasNextPage ? '+' : ''} 条`
  const orgLabel = auth.organizations.includes('*') || auth.organizations.length === 0
    ? '全域组织'
    : auth.organizations.join(' / ')
  const shopLabel = auth.shops.includes('*') || auth.shops.length === 0 ? '全部店铺' : auth.shops.join(' / ')

  useEffect(() => setNavOpen(false), [location.pathname])
  useEffect(() => {
    if (!searchOpen) return
    return () => { requestAnimationFrame(() => searchTrigger.current?.focus()) }
  }, [searchOpen])
  useEffect(() => {
    if (!navOpen) return
    const desktop = window.matchMedia('(min-width: 721px)')
    const closeOnDesktop = (event: MediaQueryListEvent) => { if (event.matches) setNavOpen(false) }
    desktop.addEventListener('change', closeOnDesktop)
    navClose.current?.focus()
    return () => {
      desktop.removeEventListener('change', closeOnDesktop)
      requestAnimationFrame(() => menuTrigger.current?.focus())
    }
  }, [navOpen])
  useEffect(() => {
    const handleKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        setNavOpen(false)
        setQuery('')
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
      <aside id="console-navigation" inert={searchOpen} role={navOpen ? 'dialog' : undefined} aria-modal={navOpen || undefined} aria-label={navOpen ? '工作台菜单' : undefined} className={`sidebar ${navOpen ? 'open' : ''}`} onKeyDown={event => {
        if (!navOpen) return
        if (event.key === 'Escape') { event.preventDefault(); setNavOpen(false); return }
        if (event.key !== 'Tab') return
        const lastLink = Array.from(event.currentTarget.querySelectorAll('a')).at(-1)
        if (event.shiftKey && event.target === navClose.current && lastLink) { event.preventDefault(); lastLink.focus() }
        if (!event.shiftKey && (event.target === lastLink || !lastLink)) { event.preventDefault(); navClose.current?.focus() }
      }}>
        <div className="brand"><span className="brand-mark" aria-hidden="true">M</span><span><strong>Meridian</strong><small>营销中枢</small></span><button ref={navClose} className="mobile-close" type="button" onClick={() => setNavOpen(false)} aria-label="关闭菜单"><X size={18} /></button></div>
        <nav aria-label="主导航">
          {visibleNav.map((group) => (
            <div className="nav-group" key={group.label}>
              <p className="nav-label">{group.label}</p>
              {group.items.map(({ label, path, icon: Icon }) => (
                <NavLink end={path === '/'} className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`} to={path} key={path} aria-label={label} title={label}>
                  <Icon size={17} aria-hidden="true" /><span>{label}</span>
                  {path === '/governance' && openApprovals > 0 && <b className="nav-badge">{openApprovalLabel}</b>}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>
        <div className="sidebar-foot">
          <div className={`health-orb ${api.demoMode || activeReleases > 0 && !releases.isError ? 'ok' : 'unknown'}`} />
          <div>
            <strong>{auth.tenantId || '未声明租户'}</strong>
            <small>{api.demoMode ? '演示模式 · 非生产数据' : releaseSummary}</small>
          </div>
        </div>
      </aside>
      {navOpen && <button className="nav-backdrop" type="button" tabIndex={-1} aria-hidden="true" onClick={() => setNavOpen(false)} />}
      <main inert={navOpen || searchOpen}>
        <header className="topbar">
          <button ref={menuTrigger} className="mobile-menu" type="button" aria-label="打开菜单" aria-controls="console-navigation" aria-expanded={navOpen} onClick={() => setNavOpen(true)}><Menu size={19} /></button>
          <div className="tenant-switch" title="业务租户是货主。创建活动只能看见同一货主已投放的商品。切换需重新登录。">
            <span className="scope-icon"><Building2 size={17} aria-hidden="true" /></span><div className="scope-info"><strong title={auth.tenantId}>业务租户 · {auth.tenantId || '未声明'}</strong><small title={`${orgLabel} · ${shopLabel}`}>{orgLabel} · {shopLabel}</small></div>
          </div>
          <button ref={searchTrigger} className="global-search" type="button" aria-label="搜索模块" aria-haspopup="dialog" onClick={() => { setQuery(''); setSearchOpen(true) }}><Search size={17} aria-hidden="true" /><span>搜索模块…</span><kbd aria-hidden="true"><Command size={11} /> K</kbd></button>
          {canOpenGovernance && <button className="icon-button" type="button" aria-label={openApprovals > 0 ? `${openApprovalLabel} 条可见待审核` : '审核与风险'} title="审核与风险" onClick={() => navigate('/governance')}>
            <ClipboardCheck size={18} />
            {openApprovals > 0 && <i />}
          </button>}
          <button className="profile" type="button" onClick={() => setLogoutOpen(true)} title="账户" aria-label="账户">
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
          <section className="command-panel" onMouseDown={event => event.stopPropagation()} onKeyDown={event => {
            if (event.key !== 'Tab') return
            const controls = Array.from(event.currentTarget.querySelectorAll<HTMLElement>('input, button'))
            const first = controls[0], last = controls.at(-1)
            if (event.shiftKey && event.target === first) { event.preventDefault(); last?.focus() }
            if (!event.shiftKey && event.target === last) { event.preventDefault(); first?.focus() }
          }}>
            <div className="command-input"><Search size={18} aria-hidden="true" /><h2 id="command-title" className="sr-only">全局搜索</h2><input autoFocus aria-label="模块名称" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="输入模块名称…" onKeyDown={event => {
              if (event.key === 'Enter' && results[0]) { navigate(results[0].path); setSearchOpen(false) }
            }} /><kbd>ESC</kbd></div>
            <div className="command-results">{results.length ? results.map(({ label, path, group, icon: Icon }) => <button key={path} type="button" onClick={() => { navigate(path); setSearchOpen(false) }}><Icon size={16} /><span>{label}</span><small>{group}</small></button>) : <p role="status">没有匹配的模块</p>}</div>
          </section>
        </div>
      )}
      {logoutOpen && (
        <Modal title="退出登录" description="将结束当前运营会话并返回身份提供方。" onClose={() => setLogoutOpen(false)}>
          <div className="danger-confirm">
            <p>确认退出「{auth.displayName}」吗？退出后需重新登录，未保存的修改可能丢失。</p>
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
