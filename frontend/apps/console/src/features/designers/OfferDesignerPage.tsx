import { useQuery } from '@tanstack/react-query'
import { useLocation, useSearchParams } from 'react-router-dom'
import { api } from '../../shared/api/client'
import { campaignNameFromLocationState, designerHeading } from './designerBinding'
import { LowCodeDesigner } from './LowCodeDesigner'
import { OFFER_UNBOUND_TITLE, OFFER_UNBOUND_VERSION, offerCanvas } from './offerCanvas'

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
  const canvas = offerCanvas()
  return <LowCodeDesigner
    key={campaignId ?? 'offer-draft'}
    title={heading.title}
    version={heading.version}
    dialect="OFFER_DECISION_DAG"
    definitionId={campaignId ? `offer-${campaignId}` : 'offer-draft'}
    campaignId={campaignId}
    nodes={canvas.nodes}
    edges={canvas.edges}
    palette={[
      { label: '商品范围', subtitle: '类目 / 店铺 / 品牌', tone: 'blue' },
      { label: '条件分支', subtitle: 'typed predicate', tone: 'violet' },
      { label: '优惠候选', subtitle: '满减 / 折扣 / 一口价', tone: 'teal' },
      { label: '权益候选', subtitle: '券 / 积分 / 赠品', tone: 'amber' },
      { label: '互斥策略', subtitle: '兼容 / 择优 / 封顶', tone: 'slate' },
    ]}
    problems={canvas.problems}
  />
}
