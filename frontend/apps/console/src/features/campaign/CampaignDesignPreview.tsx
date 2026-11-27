import { useQuery } from '@tanstack/react-query'
import { ArrowRight, ClipboardList } from 'lucide-react'
import { Link } from 'react-router-dom'
import { Badge, Button, EmptyState, PageHeader, Panel, StateBanner } from '../../components/ui'
import { displayTimestamp } from '../../components/dateTime'
import { api } from '../../shared/api/client'
import { problemDetail } from '../../shared/api/problem'
import { releaseScopeKey } from '../../shared/api/useReleasePages'
import { useAuth } from '../../shared/auth/useAuth'
import { CAMPAIGN_TYPE } from '../../shared/model/campaignType'
import { CampaignWorkbench } from './CampaignWorkbench'
import './campaign-design-preview.css'

const labels = { DRAFT: '草稿', IN_REVIEW: '审核中', APPROVED: '已批准', ACTIVE: '进行中', PAUSED: '已暂停', ENDED: '已结束' } as const

export function CampaignPreviewList() {
  const auth = useAuth()
  const query = useQuery({ queryKey: ['campaigns', releaseScopeKey(auth)], queryFn: api.campaigns, enabled: !api.demoMode })
  const campaigns = query.data?.filter(({ campaignType }) => campaignType === CAMPAIGN_TYPE.STANDARD)
  return <div className="workspace campaign-design-preview">
    <PageHeader title="营销活动配置" description="选择当前身份有权查看的标准营销活动，核对范围、规则和发布路径。"
      actions={<Link to="/campaigns" className="button secondary">返回现有活动列表</Link>} />
    <Panel className="cdp-index">
      <div className="cdp-index-heading"><ClipboardList size={18} aria-hidden="true" /><h2>标准营销活动</h2></div>
      {api.demoMode ? <StateBanner tone="info" title="营销服务尚未连接" detail="连接营销服务后可读取真实活动。" />
        : query.isPending ? <StateBanner tone="loading" title="正在读取活动" detail="读取当前可见范围。" />
        : query.isError ? <StateBanner tone="error" title="活动读取失败" detail={problemDetail(query.error)}
          action={<Button onClick={() => void query.refetch()}>重试</Button>} />
        : !campaigns?.length ? <EmptyState title="没有可配置的标准活动" detail="当前营销身份的可见范围内没有标准活动。" />
        : <div className="cdp-index-scroll"><table>
          <thead><tr><th>活动名称</th><th>活动编号</th><th>门店</th><th>状态</th><th>创建时间（北京时间）</th><th>配置入口</th></tr></thead>
          <tbody>{campaigns.map((campaign) => <tr key={campaign.id}>
            <td>{campaign.name}</td><td>{campaign.businessNo ?? campaign.id}</td>
            <td>{campaign.shopId || '全部门店'}</td><td><Badge>{labels[campaign.status]}</Badge></td>
            <td>{displayTimestamp(campaign.createdAt)}</td><td><Link className="button secondary"
              to={`/design-preview/campaigns/${encodeURIComponent(campaign.id)}`}>查看配置<ArrowRight size={14} aria-hidden="true" /></Link></td>
          </tr>)}</tbody>
        </table></div>}
    </Panel>
  </div>
}

export function CampaignPreviewDetail() {
  return <CampaignWorkbench designPreview />
}
