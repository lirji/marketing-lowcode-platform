import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Boxes, Plus } from 'lucide-react'
import { Badge, Button, DemoBanner, EmptyState, Modal, PageHeader, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { PageTabs } from '../../components/PageTabs'
import { ListToolbar } from '../../components/ListToolbar'
import { api } from '../../shared/api/client'
import { problemDetail } from '../../shared/api/problem'
import { useAuth } from '../../shared/auth/useAuth'

const assetStateLabels: Record<string, string> = { ACTIVE: '已生效', READY: '已就绪', DRAFT: '草稿', STALE: '已过期' }

function assetValue(value: string, column: string): string {
  if (!value) return '—'
  if (column === '状态') return assetStateLabels[value] ?? value
  if (column === '创建时间' && !Number.isNaN(Date.parse(value))) {
    return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', timeZone: 'Asia/Shanghai' }).format(new Date(value))
  }
  return value
}

const tabs = ['Audience 快照', '权益与券', '消息模板', '字段与节点'] as const

export function AssetsPage() {
  const auth = useAuth()
  const visibleTabs = tabs.filter((tab) => {
    if (tab === 'Audience 快照') return auth.hasPermission('audience:read')
    if (tab === '权益与券') return auth.hasPermission('benefit:read')
    if (tab === '消息模板') return auth.hasPermission('template:read')
    return auth.hasAnyPermission(['audience-field:read', 'definition:read'])
  })
  const [active, setActive] = useState<(typeof tabs)[number]>('Audience 快照')
  const current = visibleTabs.includes(active) ? active : visibleTabs[0]
  const live = !api.demoMode
  const audiences = useQuery({ queryKey: ['audiences'], queryFn: api.audiences, enabled: live && current === 'Audience 快照' })
  const benefits = useQuery({ queryKey: ['benefits'], queryFn: api.benefits, enabled: live && current === '权益与券' })
  const templates = useQuery({ queryKey: ['templates'], queryFn: api.templates, enabled: live && current === '消息模板' })
  const fields = useQuery({ queryKey: ['fields'], queryFn: api.fields, enabled: live && current === '字段与节点' && auth.hasPermission('audience-field:read') })
  const nodes = useQuery({ queryKey: ['node-registry'], queryFn: api.nodeRegistry, enabled: live && current === '字段与节点' && auth.hasPermission('definition:read') })
  const createTo = auth.hasPermission('audience:write') ? '/designers/audience' : auth.hasPermission('benefit:write') ? '/designers/benefit' : undefined

  return <div className="workspace assets-page"><PageHeader eyebrow="资产与规则" title="营销资产" description="集中查看可复用的人群、权益、消息模板和数据字段，核对版本与状态。" actions={!live || !createTo ? <Button tone="primary" disabled title={live ? '缺少写入权限' : '演示模式不创建资产'}><Plus size={15} />创建资产</Button> : <Link className="button primary" to={createTo}><Plus size={15} />去设计器</Link>} /><DemoBanner /><PageTabs label="资产分类" items={visibleTabs.map((tab) => ({ value: tab, label: tab }))} value={current} onChange={setActive}>
    {current === 'Audience 快照' && <>
      {audiences.isError && <StateBanner tone="error" title="人群加载失败" detail={problemDetail(audiences.error)} />}
      <Panel><PanelHeader eyebrow="AUDIENCE SEGMENTS" title="可用人群版本" />{audiences.isPending && live ? <div className="skeleton-list" aria-label="正在加载人群"><i /><i /></div> : (audiences.data ?? []).length === 0 ? <EmptyState title="没有人群定义" detail="控制面尚未返回人群。请到 Audience Builder 保存后再回到这里。" /> : <AssetTable key="人群" label="人群" columns={['规则指纹', '创建时间', '状态']} rows={(audiences.data ?? []).map((item) => [item.name, `${item.segmentId} · v${item.version}`, item.ruleHash ?? '—', item.createdAt ?? '', item.status])} />}</Panel>
    </>}
    {current === '权益与券' && <>
      {benefits.isError && <StateBanner tone="error" title="权益加载失败" detail={problemDetail(benefits.error)} />}
      <Panel><PanelHeader eyebrow="BENEFIT CATALOG" title="权益定义" />{benefits.isPending && live ? <div className="skeleton-list" aria-label="正在加载权益"><i /><i /></div> : (benefits.data ?? []).length === 0 ? <EmptyState title="没有权益定义" detail="控制面尚未返回权益。请到权益与资金保存后再回到这里。" /> : <AssetTable key="权益" label="权益" columns={['绑定 SKU', '创建时间', '状态']} rows={(benefits.data ?? []).map((item) => [item.name, `${item.benefitId} · v${item.version}`, item.benefitSkuId ?? '未绑定 SKU', item.createdAt ?? '', item.status])} />}</Panel>
    </>}
    {current === '消息模板' && <>
      {templates.isError && <StateBanner tone="error" title="模板加载失败" detail={problemDetail(templates.error)} />}
      <Panel><PanelHeader eyebrow="ENGAGEMENT TEMPLATES" title="渠道模板" />{templates.isPending && live ? <div className="skeleton-list" aria-label="正在加载模板"><i /><i /></div> : (templates.data ?? []).length === 0 ? <EmptyState title="没有消息模板" detail="控制面尚未返回模板。" /> : <AssetTable key="消息模板" label="消息模板" columns={['渠道', '所需变量', '状态']} rows={(templates.data ?? []).map((item) => [item.templateId, `v${item.version}`, item.channel, item.requiredVariables.join(',') || '—', item.state ?? '—'])} />}</Panel>
    </>}
    {current === '字段与节点' && <div className="asset-registry">
      <Panel><PanelHeader eyebrow="FIELD REGISTRY" title="数据字段" />{fields.isError && <StateBanner tone="error" title="字段加载失败" detail={problemDetail(fields.error)} />}{fields.isPending && live ? <div className="skeleton-list" aria-label="正在加载字段"><i /><i /></div> : (fields.data ?? []).length === 0 ? <EmptyState title="没有注册字段" detail="需要 audience-field:read，且字段目录有数据。" /> : <AssetTable key="数据字段" label="数据字段" columns={['所属方', '数据来源', '分类']} rows={(fields.data ?? []).map((item) => [item.fieldId, item.valueType ?? '—', item.owner ?? '—', item.provenance ?? '—', item.classification ?? '—'])} />}</Panel>
      <Panel><PanelHeader eyebrow="NODE REGISTRY" title="低代码节点" />{nodes.isError && <StateBanner tone="error" title="节点注册表加载失败" detail={problemDetail(nodes.error)} />}{nodes.isPending && live ? <div className="skeleton-list" aria-label="正在加载节点"><i /><i /></div> : (nodes.data ?? []).length === 0 ? <EmptyState title="没有已发布节点" detail="需要 definition:read。" /> : <AssetTable key="低代码节点" label="低代码节点" columns={['支持方言', '运行目标', '副作用']} rows={(nodes.data ?? []).map((item) => [item.stableTypeId, `${item.stableTypeId}@${item.semanticVersion}`, item.dialects.join(','), item.runtimeTarget ?? '', item.sideEffect ?? 'NONE'])} />}</Panel>
    </div>}
  </PageTabs></div>
}

function AssetTable({ rows, columns, label }: { rows: string[][]; columns: [string, string, string]; label: string }) {
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState<string[] | null>(null)
  const filtered = rows.filter((row) => [...row, assetValue(row[4], columns[2])].join(' ').toLowerCase().includes(query.trim().toLowerCase()))
  return <>
    <ListToolbar query={query} onQueryChange={setQuery} searchLabel={`搜索${label}`} placeholder={`搜索${label}名称、标识或属性`} count={filtered.length} unit="项" onReset={query ? () => setQuery('') : undefined} />
    {filtered.length === 0 ? <EmptyState title="没有匹配的资产" detail="调整关键词，或清除搜索查看当前分类下的资产。" action={<Button onClick={() => setQuery('')}>清除搜索</Button>} /> : <div className="table-scroll" role="region" aria-label={`${label}列表`} tabIndex={0}>
      <table className="asset-list" aria-label={label}>
        <thead><tr><th scope="col">名称 / 版本</th>{columns.map((column) => <th key={column} scope="col">{column}</th>)}<th scope="col">操作</th></tr></thead>
        <tbody>{filtered.map((row) => {
          const [name, id, metric, watermark, state] = row
          return <tr key={`${name}:${id}`}><td><div className="asset-identity"><span className="asset-avatar"><Boxes size={17} /></span><div><strong className="record-name">{name}</strong><small className="record-description" title={id}>{id}</small></div></div></td><td><span className="cell-preview" title={metric}>{metric || '—'}</span></td><td><span className="cell-preview" title={watermark}>{assetValue(watermark, columns[1])}</span></td><td><Badge tone={state === 'ACTIVE' || state === 'READY' || state === 'NONE' ? 'good' : state === 'STALE' ? 'danger' : 'neutral'}>{assetValue(state, columns[2])}</Badge></td><td><button className="row-action" type="button" aria-label={`查看${name}`} onClick={() => setSelected(row)}>查看详情</button></td></tr>
        })}</tbody>
      </table>
    </div>}
    {selected && <Modal title={selected[0]} description={`${label} · 当前版本信息`} onClose={() => setSelected(null)}>
      <dl className="detail-facts"><div><dt>名称</dt><dd>{selected[0]}</dd></div><div><dt>标识 / 版本</dt><dd>{selected[1]}</dd></div>{columns.map((column, index) => <div key={column}><dt>{column === '创建时间' ? '创建时间（北京时间）' : column}</dt><dd title={selected[index + 2]}>{assetValue(selected[index + 2], column)}</dd></div>)}</dl>
      <footer className="modal-actions"><Button onClick={() => setSelected(null)}>关闭详情</Button></footer>
    </Modal>}
  </>
}
