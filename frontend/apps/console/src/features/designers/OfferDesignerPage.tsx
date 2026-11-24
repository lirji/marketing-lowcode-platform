import { CAMPAIGN_TYPE, isKnownCampaignType } from '../../shared/model/campaignType'
import { StateBanner } from '../../components/ui'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useLocation, useSearchParams } from 'react-router-dom'
import { api } from '../../shared/api/client'
import { campaignNameFromLocationState, designerHeading } from './designerBinding'
import { DesignerWorkspace } from './DesignerWorkspace'
import { LowCodeDesigner } from './LowCodeDesigner'
import { OFFER_UNBOUND_TITLE, OFFER_UNBOUND_VERSION, offerCanvas } from './offerCanvas'

const DIALECT = 'OFFER_DECISION_DAG'
const DESIGNER_TITLE = 'Offer 设计器'

export function OfferDesignerPage() {
  const [params] = useSearchParams()
  const location = useLocation()
  const campaignId = params.get('campaignId') ?? undefined
  const campaigns = useQuery({
    queryKey: ['campaigns'],
    queryFn: api.campaigns,
    enabled: Boolean(campaignId) && !api.demoMode,
  })
  const campaignName = campaigns.data?.find((item) => item.id === campaignId)?.name
    ?? campaignNameFromLocationState(location.state)
  const heading = designerHeading({
    campaignId,
    campaignName,
    boundSuffix: 'Offer',
    demoTitle: OFFER_UNBOUND_TITLE,
    demoVersion: OFFER_UNBOUND_VERSION,
  })
  if (campaignId && !api.demoMode && !campaigns.data?.some((item) => item.id === campaignId && isKnownCampaignType(item.campaignType))) {
    return <DesignerWorkspace eyebrow={DIALECT} title={DESIGNER_TITLE} notices={
      <StateBanner tone={campaigns.isError ? 'error' : 'info'} title={campaigns.isPending ? '正在确认活动类型' : '无法确认活动类型'} detail="取得当前活动的真实类型后才能编辑，请返回活动列表刷新后重试。"
        action={<Link className="button secondary" to="/campaigns">返回计划与活动</Link>} />
    } />
  }
  if (campaigns.data?.find((item) => item.id === campaignId)?.campaignType === CAMPAIGN_TYPE.REFERRAL) {
    return <DesignerWorkspace eyebrow={DIALECT} title={DESIGNER_TITLE} notices={
      <StateBanner tone="info" title="此活动属于邀请有礼" detail="请使用邀请有礼规则设计器，标准 Offer / Journey 不适用于该活动。"
        action={<Link className="button secondary" to={`/designers/referral?campaignId=${encodeURIComponent(campaignId ?? '')}`}>打开邀请有礼设计器</Link>} />
    } />
  }
  const canvas = offerCanvas()
  return <LowCodeDesigner
    key={campaignId ?? 'offer-draft'}
    title={heading.title}
    version={heading.version}
    dialect={DIALECT}
    definitionId={campaignId ? `offer-${campaignId}` : 'offer-draft'}
    campaignId={campaignId}
    nodes={canvas.nodes}
    edges={canvas.edges}
    problems={canvas.problems}
  />
}
