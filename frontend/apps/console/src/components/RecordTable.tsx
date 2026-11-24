import type { ReactNode } from 'react'
import { displayInstant } from './dateTime'

type RecordColumn = string | { label: string; width: number }

/** 记录列表统一按字段分列；仅负责展示，查询、权限和行操作仍由业务页面控制。 */
export function RecordTable({ label, columns, children, actions = false }: { label: string; columns: readonly RecordColumn[]; children: ReactNode; actions?: boolean }) {
  const minWidth = columns.reduce((total, column) => total + (typeof column === 'string' ? 160 : column.width), 0)
  return <div className="table-scroll record-table-scroll" role="region" aria-label={`${label}表格`} tabIndex={0}>
    <table className={`record-table${actions ? ' has-actions' : ''}`} aria-label={label} style={{ minWidth: Math.max(560, minWidth) }}>
      <thead><tr>{columns.map(column => typeof column === 'string'
        ? <th scope="col" key={column}>{column}</th>
        : <th scope="col" key={column.label} style={{ width: column.width }}>{column.label}</th>)}</tr></thead>
      <tbody>{children}</tbody>
    </table>
  </div>
}

/** 空时间必须显示缺失，不能被选择器的占位提示或其他时间字段替代。 */
export function RecordTime({ value }: { value?: string | null }) {
  return value ? <time dateTime={value}>{displayInstant(value)}</time> : <>—</>
}
