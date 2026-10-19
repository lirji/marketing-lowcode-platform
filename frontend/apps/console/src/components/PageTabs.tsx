import { useId, useRef, type ReactNode } from 'react'

/** 横向页签共用键盘行为，确保窄屏滚动后仍能定位当前内容。 */
export function PageTabs<T extends string>({ label, items, value, onChange, children }: {
  label: string; items: readonly { value: T; label: string }[]; value?: T
  onChange: (value: T) => void; children: ReactNode
}) {
  const id = useId()
  const buttons = useRef<(HTMLButtonElement | null)[]>([])
  return <>
    <div className="page-tabs" role="tablist" aria-label={label}>
      {items.map((item, index) => <button key={item.value} ref={(node) => { buttons.current[index] = node }} id={`${id}-${item.value}`} type="button" role="tab" tabIndex={item.value === value ? 0 : -1} aria-selected={item.value === value} aria-controls={`${id}-panel`} onClick={() => onChange(item.value)} onKeyDown={(event) => {
        const next = event.key === 'ArrowRight' ? (index + 1) % items.length
          : event.key === 'ArrowLeft' ? (index - 1 + items.length) % items.length
            : event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1 : undefined
        if (next === undefined) return
        event.preventDefault()
        onChange(items[next].value)
        buttons.current[next]?.focus()
      }}>{item.label}</button>)}
    </div>
    <div className="page-tab-content" id={`${id}-panel`} role="tabpanel" aria-labelledby={value ? `${id}-${value}` : undefined}>{children}</div>
  </>
}
