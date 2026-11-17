import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Plus } from 'lucide-react'
import { TemplateManager } from './TemplateManager'
import { AssetTable } from './AssetTable'
import { AudienceAssetList } from './AudienceAssetList'
import { BenefitAssetList } from './BenefitAssetList'
import { Button, DemoBanner, EmptyState, PageHeader, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { PageTabs } from '../../components/PageTabs'
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
  const identity = JSON.stringify([auth.tenantId, auth.subject, auth.organizations, auth.shops, auth.permissions])
  const fields = useQuery({ queryKey: ['fields', identity], queryFn: api.fields, enabled: live && current === '字段与节点' && auth.hasPermission('audience-field:read') })
  const nodes = useQuery({ queryKey: ['node-registry', identity], queryFn: api.nodeRegistry, enabled: live && current === '字段与节点' && auth.hasPermission('definition:read') })
  const createTo = current === 'Audience 快照' && auth.hasPermission('audience:write') ? '/designers/audience' : current === '权益与券' && auth.hasPermission('benefit:write') ? '/designers/benefit' : undefined

  return <div className="workspace assets-page"><PageHeader eyebrow="资产与规则" title="营销资产" description="集中查看可复用的人群、权益、消息模板和数据字段，核对版本与状态。" actions={current === '消息模板' || current === '字段与节点' ? undefined : !live || !createTo ? <Button tone="primary" disabled title={live ? '缺少写入权限' : '演示模式不创建资产'}><Plus size={15} />创建资产</Button> : <Link className="button primary" to={createTo}><Plus size={15} />去设计器</Link>} /><DemoBanner /><PageTabs label="资产分类" items={visibleTabs.map((tab) => ({ value: tab, label: tab }))} value={current} onChange={setActive}>
    {current === 'Audience 快照' && <AudienceAssetList key={JSON.stringify([auth.tenantId, auth.subject, auth.organizations, auth.shops, auth.permissions])} />}
    {current === '权益与券' && <BenefitAssetList key={JSON.stringify([auth.tenantId, auth.subject, auth.organizations, auth.shops, auth.permissions])} />}
    {current === '消息模板' && <TemplateManager key={[auth.tenantId, auth.subject, auth.permissions.join(',')].join(':')} />}
    {current === '字段与节点' && (!live ? <Panel><EmptyState title="字段与节点需要连接服务" detail="连接后按当前身份权限读取字段目录与节点注册表；静态预览不代表已发布。" /></Panel> : <div className="asset-registry">
      {auth.hasPermission('audience-field:read') && <Panel><PanelHeader eyebrow="FIELD REGISTRY" title="数据字段" />{fields.isError && <StateBanner tone="error" title="字段加载失败" detail={problemDetail(fields.error)} />}{fields.isPending && live ? <div className="skeleton-list" aria-label="正在加载字段"><i /><i /></div> : (fields.data ?? []).length === 0 ? <EmptyState title="没有注册字段" detail="需要 audience-field:read，且字段目录有数据。" /> : <AssetTable key="数据字段" label="数据字段" columns={['字段 ID', '值类型', '所属方', '数据来源', '分类']} rows={(fields.data ?? []).map((item) => [item.fieldId, item.valueType ?? '—', item.owner ?? '—', item.provenance ?? '—', item.classification ?? '—'])} />}</Panel>}
      {auth.hasPermission('definition:read') && <Panel><PanelHeader eyebrow="NODE REGISTRY" title="低代码节点" />{nodes.isError && <StateBanner tone="error" title="节点注册表加载失败" detail={problemDetail(nodes.error)} />}{nodes.isPending && live ? <div className="skeleton-list" aria-label="正在加载节点"><i /><i /></div> : (nodes.data ?? []).length === 0 ? <EmptyState title="没有已发布节点" detail="需要 definition:read。" /> : <AssetTable key="低代码节点" label="低代码节点" columns={['节点类型 ID', '语义版本', '支持方言', '运行目标', '副作用']} rows={(nodes.data ?? []).map((item) => [item.stableTypeId, item.semanticVersion, item.dialects.join(','), item.runtimeTarget ?? '', item.sideEffect ?? 'NONE'])} />}</Panel>}
    </div>)}
  </PageTabs></div>
}
