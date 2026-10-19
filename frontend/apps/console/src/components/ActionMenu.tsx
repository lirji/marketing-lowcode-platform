import { useCallback, useEffect, useId, useRef, useState, type CSSProperties, type KeyboardEvent } from 'react'
import { createPortal } from 'react-dom'
import { ChevronDown } from 'lucide-react'
import { Link } from 'react-router-dom'

type MenuItem = { label: string; to: string; state?: Record<string, string> }

/** 列表中的低频入口收进菜单；浮层挂到 body，避免被横向滚动表格裁切。 */
export function ActionMenu({ label, subject, items }: { label: string; subject: string; items: MenuItem[] }) {
  const [open, setOpen] = useState(false)
  const [position, setPosition] = useState<CSSProperties>({})
  const trigger = useRef<HTMLButtonElement>(null)
  const menu = useRef<HTMLDivElement>(null)
  const id = useId()

  const positionMenu = useCallback(() => {
    const rect = trigger.current?.getBoundingClientRect()
    if (!rect) return
    const width = Math.min(220, window.innerWidth - 24)
    setPosition({
      width,
      left: Math.max(12, Math.min(rect.right - width, window.innerWidth - width - 12)),
      ...(window.innerHeight - rect.bottom < 180
        ? { bottom: window.innerHeight - rect.top + 6 }
        : { top: rect.bottom + 6 }),
    })
  }, [])
  const show = () => { positionMenu(); setOpen(true) }

  useEffect(() => {
    if (!open) return
    menu.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus({ preventScroll: true })
    const dismiss = (event: Event) => {
      if (event.target instanceof Node && (menu.current?.contains(event.target) || trigger.current?.contains(event.target))) return
      setOpen(false)
    }
    const resize = () => setOpen(false)
    document.addEventListener('pointerdown', dismiss)
    // 键盘聚焦窄屏表格会自动横向滚动；跟随锚点，避免刚打开的菜单被误关闭。
    window.addEventListener('scroll', positionMenu, true)
    window.addEventListener('resize', resize)
    return () => {
      document.removeEventListener('pointerdown', dismiss)
      window.removeEventListener('scroll', positionMenu, true)
      window.removeEventListener('resize', resize)
    }
  }, [open, positionMenu])

  const handleKeys = (event: KeyboardEvent) => {
    if (event.key === 'Escape' || event.key === 'Tab') {
      if (event.key === 'Escape') event.preventDefault()
      trigger.current?.focus()
      setOpen(false)
      return
    }
    const nodes = Array.from(menu.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [])
    const index = nodes.indexOf(document.activeElement as HTMLElement)
    const next = event.key === 'ArrowDown' ? (index + 1) % nodes.length
      : event.key === 'ArrowUp' ? (index - 1 + nodes.length) % nodes.length
        : event.key === 'Home' ? 0 : event.key === 'End' ? nodes.length - 1 : undefined
    if (next !== undefined) { event.preventDefault(); nodes[next]?.focus() }
  }

  if (items.length === 0) return null
  return <>
    <button ref={trigger} className="row-menu-trigger" type="button" aria-label={`${label}：${subject}`} aria-haspopup="menu" aria-expanded={open} aria-controls={open ? id : undefined} onClick={() => open ? setOpen(false) : show()} onKeyDown={(event) => {
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') { event.preventDefault(); show() }
    }}>{label}<ChevronDown size={14} aria-hidden="true" /></button>
    {open && createPortal(<div ref={menu} id={id} role="menu" aria-label={`${subject}的${label}`} className="row-action-menu" style={position} onKeyDown={handleKeys}>
      <p className="row-menu-caption">{subject}</p>
      {items.map((item) => <Link key={item.to} role="menuitem" tabIndex={-1} to={item.to} state={item.state} onClick={() => setOpen(false)}>{item.label}</Link>)}
    </div>, document.body)}
  </>
}
