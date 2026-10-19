import type { ReactNode } from 'react'
import { displayInstant } from './dateTime'

/** 记录列表统一按字段分列；仅负责展示，查询、权限和行操作仍由业务页面控制。 */
export function RecordTable({ label, columns, children, actions = false }: { label: string; columns: readonly string[]; children: ReactNode; actions?: boolean }) {
  return <div className="table-scroll record-table-scroll" role="region" aria-label={`${label}表格`} tabIndex={0}>
    <table className={`record-table${actions ? ' has-actions' : ''}`} aria-label={label} style={{ minWidth: Math.max(560, columns.length * 160) }}>
      <thead><tr>{columns.map((column) => <th scope="col" key={column}>{column}</th>)}</tr></thead>
      <tbody>{children}</tbody>
    </table>
  </div>
}

/** 空时间必须显示缺失，不能被选择器的占位提示或其他时间字段替代。 */
export function RecordTime({ value }: { value?: string | null }) {
  return value ? <time dateTime={value}>{displayInstant(value)}</time> : <>—</>
}
