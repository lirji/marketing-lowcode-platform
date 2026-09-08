import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Plus, UsersRound } from 'lucide-react'
import { Badge, Button, DemoBanner, EmptyState, PageHeader, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { api } from '../../shared/api/client'
import { problemDetail } from '../../shared/api/problem'
import { useAuth } from '../../shared/auth/useAuth'

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

  return <div className="workspace assets-page"><PageHeader eyebrow="营销资产" title="复用经过治理的版本化资产。" description="每个定义都有来源、用途、版本、新鲜度与依赖关系，运行时只消费已发布版本。" actions={!live || !createTo ? <Button tone="primary" disabled title={live ? '缺少写入权限' : '演示模式不创建资产'}><Plus size={15} />创建资产</Button> : <Link className="button primary" to={createTo}><Plus size={15} />去设计器</Link>} /><DemoBanner /><div className="page-tabs" role="tablist">{visibleTabs.map((tab) => <button role="tab" aria-selected={current === tab} aria-controls={`tab-${tab}`} className={current === tab ? 'active' : ''} onClick={() => setActive(tab)} key={tab}>{tab}</button>)}</div>
    {current === 'Audience 快照' && <>
      {audiences.isError && <StateBanner tone="error" title="人群加载失败" detail={problemDetail(audiences.error)} />}
      <Panel><PanelHeader eyebrow="AUDIENCE SEGMENTS" title="可用人群版本" />{audiences.isPending && live ? <div className="skeleton-list" aria-label="正在加载人群"><i /><i /></div> : (audiences.data ?? []).length === 0 ? <EmptyState title="没有人群定义" detail="控制面尚未返回人群。请到 Audience Builder 保存后再回到这里。" /> : <AssetTable rows={(audiences.data ?? []).map((item) => [item.name, `${item.segmentId} · v${item.version}`, item.ruleHash ?? '—', item.createdAt ?? '', item.status])} />}</Panel>
    </>}
    {current === '权益与券' && <>
      {benefits.isError && <StateBanner tone="error" title="权益加载失败" detail={problemDetail(benefits.error)} />}
      <Panel><PanelHeader eyebrow="BENEFIT CATALOG" title="权益定义" />{benefits.isPending && live ? <div className="skeleton-list" aria-label="正在加载权益"><i /><i /></div> : (benefits.data ?? []).length === 0 ? <EmptyState title="没有权益定义" detail="控制面尚未返回权益。请到权益与资金保存后再回到这里。" /> : <AssetTable rows={(benefits.data ?? []).map((item) => [item.name, `${item.benefitId} · v${item.version}`, item.benefitSkuId ?? '未绑定 SKU', item.createdAt ?? '', item.status])} />}</Panel>
    </>}
    {current === '消息模板' && <>
      {templates.isError && <StateBanner tone="error" title="模板加载失败" detail={problemDetail(templates.error)} />}
      <Panel><PanelHeader eyebrow="ENGAGEMENT TEMPLATES" title="渠道模板" />{templates.isPending && live ? <div className="skeleton-list" aria-label="正在加载模板"><i /><i /></div> : (templates.data ?? []).length === 0 ? <EmptyState title="没有消息模板" detail="控制面尚未返回模板。" /> : <AssetTable rows={(templates.data ?? []).map((item) => [item.templateId, `v${item.version}`, item.channel, item.requiredVariables.join(',') || '—', item.state ?? '—'])} />}</Panel>
    </>}
    {current === '字段与节点' && <div className="asset-registry">
      <Panel><PanelHeader eyebrow="FIELD REGISTRY" title="数据字段" />{fields.isError && <StateBanner tone="error" title="字段加载失败" detail={problemDetail(fields.error)} />}{fields.isPending && live ? <div className="skeleton-list" aria-label="正在加载字段"><i /><i /></div> : (fields.data ?? []).length === 0 ? <EmptyState title="没有注册字段" detail="需要 audience-field:read，且字段目录有数据。" /> : <AssetTable rows={(fields.data ?? []).map((item) => [item.fieldId, item.valueType ?? '—', item.owner ?? '—', item.provenance ?? '—', item.classification ?? '—'])} />}</Panel>
      <Panel><PanelHeader eyebrow="NODE REGISTRY" title="低代码节点" />{nodes.isError && <StateBanner tone="error" title="节点注册表加载失败" detail={problemDetail(nodes.error)} />}{nodes.isPending && live ? <div className="skeleton-list" aria-label="正在加载节点"><i /><i /></div> : (nodes.data ?? []).length === 0 ? <EmptyState title="没有已发布节点" detail="需要 definition:read。" /> : <AssetTable rows={(nodes.data ?? []).map((item) => [item.stableTypeId, `${item.stableTypeId}@${item.semanticVersion}`, item.dialects.join(','), item.runtimeTarget ?? '', item.sideEffect ?? 'NONE'])} />}</Panel>
    </div>}
  </div>
}

function AssetTable({ rows }: { rows: string[][] }) {
  return <div className="asset-table">{rows.map(([name, id, metric, watermark, state]) => <button key={id} type="button"><span className="asset-avatar"><UsersRound size={15} /></span><div><strong>{name}</strong><small>{id}</small></div><span>{metric}</span><span>{watermark}</span><Badge tone={state === 'ACTIVE' || state === 'READY' || state === 'NONE' ? 'good' : state === 'STALE' ? 'danger' : 'warn'}>{state}</Badge><i>→</i></button>)}</div>
}
