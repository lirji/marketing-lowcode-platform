import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Boxes, FileText, Plus, Tags, UsersRound } from 'lucide-react'
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
  const audiences = useQuery({ queryKey: ['audiences'], queryFn: api.audiences, enabled: !api.demoMode && current === 'Audience 快照' })
  const benefits = useQuery({ queryKey: ['benefits'], queryFn: api.benefits, enabled: !api.demoMode && current === '权益与券' })
  const templates = useQuery({ queryKey: ['templates'], queryFn: api.templates, enabled: !api.demoMode && current === '消息模板' })
  const fields = useQuery({ queryKey: ['fields'], queryFn: api.fields, enabled: !api.demoMode && current === '字段与节点' && auth.hasPermission('audience-field:read') })
  const nodes = useQuery({ queryKey: ['node-registry'], queryFn: api.nodeRegistry, enabled: !api.demoMode && current === '字段与节点' && auth.hasPermission('definition:read') })
  const createTo = auth.hasPermission('audience:write') ? '/designers/audience' : auth.hasPermission('benefit:write') ? '/designers/benefit' : undefined

  return <div className="workspace assets-page"><PageHeader eyebrow="营销资产" title="复用经过治理的版本化资产。" description="每个定义都有来源、用途、版本、新鲜度与依赖关系，运行时只消费已发布版本。" actions={api.demoMode || !createTo ? <Button tone="primary" disabled title="演示模式或缺少写入权限"><Plus size={15} />创建资产</Button> : <Link className="button primary" to={createTo}><Plus size={15} />去设计器</Link>} /><DemoBanner /><div className="page-tabs" role="tablist">{visibleTabs.map((tab) => <button role="tab" aria-selected={current === tab} aria-controls={`tab-${tab}`} className={current === tab ? 'active' : ''} onClick={() => setActive(tab)} key={tab}>{tab}</button>)}</div>
    {current === 'Audience 快照' && (api.demoMode ? <Panel><PanelHeader eyebrow="AUDIENCE SNAPSHOTS" title="可用人群版本" /><AssetTable rows={[['PLUS 家电高意向', 'aud-ha-plus · v18', '1,248,620', '10:30:12', 'READY']]} /></Panel> : <>
      {audiences.isError && <StateBanner tone="error" title="人群加载失败" detail={problemDetail(audiences.error)} />}
      <Panel><PanelHeader eyebrow="AUDIENCE SEGMENTS" title="可用人群版本" />{(audiences.data ?? []).length === 0 ? <EmptyState title="没有人群定义" detail="GET /api/v1/audiences 为空。" /> : <AssetTable rows={(audiences.data ?? []).map((item) => [item.name, `${item.segmentId} · v${item.version}`, item.ruleHash ?? '—', item.createdAt ?? '', item.status])} />}</Panel>
    </>)}
    {current === '权益与券' && (api.demoMode ? <Panel><PanelHeader eyebrow="BENEFIT CATALOG" title="权益定义" /><AssetTable rows={[['家电满500减80券', 'coupon-ha-80 · v9', '预算 ¥20M', '库存 731K', 'DRAFT']]} /></Panel> : <>
      {benefits.isError && <StateBanner tone="error" title="权益加载失败" detail={problemDetail(benefits.error)} />}
      <Panel><PanelHeader eyebrow="BENEFIT CATALOG" title="权益定义" />{(benefits.data ?? []).length === 0 ? <EmptyState title="没有权益定义" detail="GET /api/v1/benefits 为空。" /> : <AssetTable rows={(benefits.data ?? []).map((item) => [item.name, `${item.benefitId} · v${item.version}`, item.benefitSkuId ?? '未绑定 SKU', item.createdAt ?? '', item.status])} />}</Panel>
    </>)}
    {current === '消息模板' && (api.demoMode ? <Panel><PanelHeader eyebrow="ENGAGEMENT TEMPLATES" title="渠道模板" /><AssetTable rows={[['加购召回 Push', 'tpl-cart-push · v11', 'PUSH', '变量 4/4', 'ACTIVE']]} /></Panel> : <>
      {templates.isError && <StateBanner tone="error" title="模板加载失败" detail={problemDetail(templates.error)} />}
      <Panel><PanelHeader eyebrow="ENGAGEMENT TEMPLATES" title="渠道模板" />{(templates.data ?? []).length === 0 ? <EmptyState title="没有消息模板" detail="GET /api/v1/templates 为空。" /> : <AssetTable rows={(templates.data ?? []).map((item) => [item.templateId, `v${item.version}`, item.channel, item.requiredVariables.join(',') || '—', item.state ?? '—'])} />}</Panel>
    </>)}
    {current === '字段与节点' && (api.demoMode ? <div className="asset-registry"><Panel><PanelHeader eyebrow="FIELD REGISTRY" title="数据字段" /><RegistryStats icon={<Tags />} value="248" label="注册字段" /></Panel><Panel><PanelHeader eyebrow="NODE REGISTRY" title="低代码节点" /><RegistryStats icon={<Boxes />} value="42" label="稳定节点类型" /></Panel><Panel><PanelHeader eyebrow="TERMS LIBRARY" title="公示与条款" /><RegistryStats icon={<FileText />} value="96" label="不可变 Terms 快照" /></Panel></div> : <div className="asset-registry">
      <Panel><PanelHeader eyebrow="FIELD REGISTRY" title="数据字段" />{fields.isError && <StateBanner tone="error" title="字段加载失败" detail={problemDetail(fields.error)} />}{(fields.data ?? []).length === 0 ? <EmptyState title="没有注册字段" detail="需要 audience-field:read，且 GET /api/v1/fields 有数据。" /> : <AssetTable rows={(fields.data ?? []).map((item) => [item.fieldId, item.valueType ?? '—', item.owner ?? '—', item.provenance ?? '—', item.classification ?? '—'])} />}</Panel>
      <Panel><PanelHeader eyebrow="NODE REGISTRY" title="低代码节点" />{nodes.isError && <StateBanner tone="error" title="节点注册表加载失败" detail={problemDetail(nodes.error)} />}{(nodes.data ?? []).length === 0 ? <EmptyState title="没有已发布节点" detail="需要 definition:read。" /> : <AssetTable rows={(nodes.data ?? []).map((item) => [item.stableTypeId, `${item.stableTypeId}@${item.semanticVersion}`, item.dialects.join(','), item.runtimeTarget ?? '', item.sideEffect ?? 'NONE'])} />}</Panel>
    </div>)}
  </div>
}

function AssetTable({ rows }: { rows: string[][] }) {
  return <div className="asset-table">{rows.map(([name, id, metric, watermark, state]) => <button key={id} type="button"><span className="asset-avatar"><UsersRound size={15} /></span><div><strong>{name}</strong><small>{id}</small></div><span>{metric}</span><span>{watermark}</span><Badge tone={state === 'ACTIVE' || state === 'READY' || state === 'NONE' ? 'good' : state === 'STALE' ? 'danger' : 'warn'}>{state}</Badge><i>→</i></button>)}</div>
}

function RegistryStats({ icon, value, label }: { icon: React.ReactNode; value: string; label: string }) {
  return <div className="registry-stat"><span>{icon}</span><strong>{value}</strong><small>{label}</small></div>
}
