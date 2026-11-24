import { useEffect, useRef, type ButtonHTMLAttributes, type ReactNode } from 'react'
import { X } from 'lucide-react'
import { runtimeConfig } from '../shared/config/runtime'

export function PageHeader({ eyebrow, title, description, actions }: { eyebrow?: string; title: string; description: string; actions?: ReactNode }) {
  return <header className="page-heading"><div>{eyebrow && <p className="eyebrow">{eyebrow}</p>}<h1>{title}</h1><p>{description}</p></div>{actions && <div className="action-bar action-bar--end heading-actions">{actions}</div>}</header>
}

export function Button({ tone = 'secondary', className = '', ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { tone?: 'primary' | 'secondary' | 'danger' | 'ghost' }) {
  return <button type={props.type ?? 'button'} className={`button ${tone} ${className}`} {...props} />
}

export function Badge({ tone = 'neutral', children }: { tone?: 'good' | 'warn' | 'danger' | 'neutral' | 'info'; children: ReactNode }) {
  return <span className={`status ${tone}`}>{children}</span>
}

export function Panel({ className = '', children }: { className?: string; children: ReactNode }) {
  return <section className={`panel ${className}`}>{children}</section>
}

export function PanelHeader({ eyebrow, title, aside }: { eyebrow?: string; title: string; aside?: ReactNode }) {
  return <div className="panel-head"><div>{eyebrow && <p className="eyebrow">{eyebrow}</p>}<h2>{title}</h2></div>{aside && <div className="action-bar action-bar--end panel-actions">{aside}</div>}</div>
}

export function StateBanner({ tone, title, detail, action }: { tone: 'loading' | 'error' | 'warn' | 'success' | 'info'; title: string; detail: string; action?: ReactNode }) {
  return <div className={`state-banner ${tone}${action ? ' with-action' : ''}`}><div className="state-message" role={tone === 'error' ? 'alert' : 'status'}><i aria-hidden="true" /><div><strong>{title}</strong><span>{detail}</span></div></div>{action}</div>
}

export function DemoBanner() {
  if (!runtimeConfig.demoMode) return null
  return <StateBanner tone="warn" title="静态预览模式" detail="业务数据以连接营销服务后的结果为准；当前模式不执行业务写入。" />
}

export function Modal({ title, description, children, onClose }: { title: string; description?: string; children: ReactNode; onClose: () => void }) {
  const card = useRef<HTMLElement>(null)
  // 输入框的 autoFocus 会在 effect 之前运行，因此在打开时记录原来的操作位置。
  const opener = useRef(document.activeElement instanceof HTMLElement ? document.activeElement : null)
  const close = useRef(onClose)
  useEffect(() => { close.current = onClose }, [onClose])
  useEffect(() => {
    const root = card.current
    const focusable = () => Array.from(root?.querySelectorAll<HTMLElement>('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])') ?? []).filter((node) => !node.hasAttribute('disabled') && node.getAttribute('tabindex') !== '-1' && node.getAttribute('type') !== 'hidden' && !node.closest('[aria-hidden="true"]'))
    if (!root?.contains(document.activeElement)) focusable()[0]?.focus()
    const onKey = (event: KeyboardEvent) => {
      if (event.defaultPrevented) return
      if (event.key === 'Escape') {
        event.preventDefault()
        close.current()
        return
      }
      if (event.key !== 'Tab') return
      const nodes = focusable()
      if (nodes.length === 0) return
      const first = nodes[0]
      const last = nodes[nodes.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('keydown', onKey)
      opener.current?.focus({ preventScroll: true })
    }
  }, [])
  return (
    <div className="modal-layer" role="dialog" aria-modal="true" aria-labelledby="modal-title" onMouseDown={onClose}>
      <section ref={card} className="modal-card" onMouseDown={(event) => event.stopPropagation()}>
        <header>
          <div><h2 id="modal-title">{title}</h2>{description && <p>{description}</p>}</div>
          <button type="button" onClick={onClose} aria-label="关闭"><X size={18} /></button>
        </header>
        {children}
      </section>
    </div>
  )
}

export function EmptyState({ title, detail, action }: { title: string; detail: string; action?: ReactNode }) {
  return <div className="empty-state"><span>◇</span><h3>{title}</h3><p>{detail}</p>{action}</div>
}
