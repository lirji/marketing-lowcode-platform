import { useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { displayInstant } from '../../components/dateTime'
import { RecordTable } from '../../components/RecordTable'
import { FieldHint } from '../../components/FieldHint'
import { ListToolbar } from '../../components/ListToolbar'
import { Badge, Button, EmptyState, Modal } from '../../components/ui'

const assetStateLabels: Record<string, string> = { ACTIVE: '已生效', READY: '已就绪', DRAFT: '草稿', STALE: '已过期', RETIRED: '已退役' }

function assetValue(value: string, column: string): string {
  if (!value) return '—'
  if (column === '状态') return assetStateLabels[value] ?? value
  if (column === '创建时间') return Number.isFinite(Date.parse(value)) ? displayInstant(value) : '时间无效'
  return value
}

export function AssetTable({ rows, columns, label, editHref, search, bodyState }: {
  rows: string[][]; columns: string[]; label: string; editHref?: (row: string[]) => string
  search?: { value: string; onChange: (value: string) => void; onRefresh: () => void; busy: boolean; countKnown: boolean }
  bodyState?: ReactNode
}) {
  const [localQuery, setLocalQuery] = useState('')
  const query = search?.value ?? localQuery
  const setQuery = search?.onChange ?? setLocalQuery
  const [selected, setSelected] = useState<string[] | null>(null)
  const displayRow = (row: string[]) => row.map((value, index) => assetValue(value, columns[index]))
  const filtered = search ? rows : rows.filter((row) => displayRow(row).some((value) => value.toLowerCase().includes(query.trim().toLowerCase())))
  return <>
    <ListToolbar query={query} onQueryChange={setQuery} searchLabel={`搜索${label}`} describedBy={`asset-search-${label}`} placeholder={`搜索${columns.slice(0, 2).join('、')}`} count={search && !search.countKnown ? undefined : filtered.length} unit={search ? "项（已加载）" : "项"} onRefresh={search?.onRefresh} busy={search?.busy} onReset={query ? () => setQuery('') : undefined} />
    <div className="collection-search-help"><FieldHint id={`asset-search-${label}`}>支持搜索{search ? `当前租户全部${label}` : "当前列表"}的{columns.join('、')}，按关键词包含匹配。</FieldHint></div>
    {bodyState ?? (filtered.length === 0 ? <EmptyState title="没有匹配的资产" detail="调整关键词，或清除搜索查看当前分类下的资产。" action={<Button onClick={() => setQuery('')}>清除搜索</Button>} /> : <RecordTable label={label} columns={[...columns.map((column) => column === '创建时间' ? '创建时间（北京时间）' : column), '操作']} actions>
      {filtered.map((row) => <tr key={row.slice(0, 3).join(':')}>
        {row.map((value, index) => <td key={columns[index]}>{columns[index] === '状态' ? <Badge tone={value === 'ACTIVE' || value === 'READY' ? 'good' : value === 'STALE' ? 'danger' : 'neutral'}>{assetValue(value, columns[index])}</Badge> : assetValue(value, columns[index])}</td>)}
        <td><button className="row-action" type="button" aria-label={`查看${row[0]}`} onClick={() => setSelected(row)}>查看详情</button>{editHref && <Link className="row-action" to={editHref(row)} aria-label={`编辑${row[0]}`}>编辑新版本</Link>}</td>
      </tr>)}
    </RecordTable>)}
    {selected && <Modal title={selected[0]} description={`${label}详情`} onClose={() => setSelected(null)}>
      <dl className="detail-facts">{columns.map((column, index) => <div key={column}><dt>{column === '创建时间' ? '创建时间（北京时间）' : column}</dt><dd>{assetValue(selected[index], column)}</dd></div>)}</dl>
      <footer className="modal-actions"><Button onClick={() => setSelected(null)}>关闭详情</Button></footer>
    </Modal>}
  </>
}
