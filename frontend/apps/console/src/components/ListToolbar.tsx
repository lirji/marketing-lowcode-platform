import type { ReactNode } from 'react'
import { RotateCw, Search, X } from 'lucide-react'
import { Button } from './ui'

const labels = { clear: '清除筛选', clearSearch: '清除搜索', refresh: '刷新列表', loading: '正在加载…' }

export function ListToolbar({ query, onQueryChange, searchLabel, placeholder, count, unit, describedBy, children, onReset, onRefresh, busy = false }: {
  query: string; onQueryChange: (value: string) => void; searchLabel: string; placeholder: string
  count?: number; unit: string; describedBy?: string; children?: ReactNode; onReset?: () => void; onRefresh?: () => void; busy?: boolean
}) {
  return <div className="list-toolbar">
    <div className="list-filters">
      <label className="field-search"><Search size={16} aria-hidden="true" /><span className="sr-only">{searchLabel}</span><input type="search" aria-describedby={describedBy} value={query} onChange={(event) => onQueryChange(event.target.value)} placeholder={placeholder} /></label>
      {query && <button className="search-clear" type="button" aria-label={labels.clearSearch} onClick={() => onQueryChange('')}><X size={15} /></button>}
      {children}
      {onReset && <Button tone="ghost" onClick={onReset}>{labels.clear}</Button>}
    </div>
    <div className="list-tools"><span className="list-count" aria-live="polite">{count === undefined ? busy ? labels.loading : '—' : <><strong>{count}</strong> {unit}</>}</span>{onRefresh && <Button className="refresh-button" onClick={onRefresh} disabled={busy} aria-label={labels.refresh} title={labels.refresh}><RotateCw size={15} className={busy ? 'is-spinning' : ''} /></Button>}</div>
  </div>
}
