import { useQuery } from '@tanstack/react-query'
import { useLocation, useSearchParams } from 'react-router-dom'
import { api } from '../../shared/api/client'
import { campaignNameFromLocationState, designerHeading } from './designerBinding'
import { LowCodeDesigner } from './LowCodeDesigner'
import { JOURNEY_UNBOUND_TITLE, JOURNEY_UNBOUND_VERSION, journeyCanvas } from './journeyCanvas'

export function JourneyDesignerPage() {
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
    boundSuffix: 'Journey',
    demoTitle: JOURNEY_UNBOUND_TITLE,
    demoVersion: JOURNEY_UNBOUND_VERSION,
  })
  const canvas = journeyCanvas()
  return <LowCodeDesigner
    key={campaignId ?? 'journey-draft'}
    title={heading.title}
    version={heading.version}
    dialect="JOURNEY_STATE_MACHINE"
    definitionId={campaignId ? `journey-${campaignId}` : 'journey-draft'}
    campaignId={campaignId}
    nodes={canvas.nodes}
    edges={canvas.edges}
    palette={[
      { label: '事件触发', subtitle: 'event-time + dedup', tone: 'blue' },
      { label: '等待时间', subtitle: '可恢复 timer', tone: 'slate' },
      { label: '条件分支', subtitle: 'snapshot variables', tone: 'violet' },
      { label: '发送消息', subtitle: 'consent gate', tone: 'teal' },
      { label: '发放权益', subtitle: '尚未接入 AwardIntent', tone: 'amber' },
      { label: 'Webhook', subtitle: 'signed connector', tone: 'blue' },
    ]}
    problems={canvas.problems}
  />
}
