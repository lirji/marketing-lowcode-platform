import { Navigate, useParams } from 'react-router-dom'
import { campaignWorkbenchPath } from './thresholdOffer'

export function CampaignPreviewList() {
  return <Navigate to="/campaigns" replace />
}

export function CampaignPreviewDetail() {
  const { campaignId = '' } = useParams()
  return <Navigate to={campaignId ? campaignWorkbenchPath(campaignId) : '/campaigns'} replace />
}
