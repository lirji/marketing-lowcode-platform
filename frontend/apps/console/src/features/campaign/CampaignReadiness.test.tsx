import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { DefinitionBundle, ReleaseView } from '../../shared/api/schemas'
import { CampaignCreateReadiness, definitionsHaveRelease } from './CampaignReadiness'

function definition(definitionId: string, version: number): DefinitionBundle {
  return { definitionId, version } as DefinitionBundle
}

function release(definitionId: string, version: number): ReleaseView {
  return {
    manifest: {
      manifestId: `manifest-${definitionId}`,
      cell: 'cell-a',
      generation: 1,
      artifacts: [{ definitionId, definitionVersion: version }],
    },
    state: 'STAGED',
    readyReplicas: 0,
    readyCapacity: 0,
  } as ReleaseView
}

describe('CampaignCreateReadiness', () => {
  it('lists required campaign assets as not ready without inventing data', () => {
    render(<CampaignCreateReadiness />)
    expect(screen.getByLabelText('活动就绪清单')).toBeInTheDocument()
    expect(screen.getByText(/权益：已绑定 ACTIVE SKU/)).toBeInTheDocument()
    expect(screen.getByText(/发布：在发布中心编译并暂存本活动定义/)).toBeInTheDocument()
    expect(screen.getAllByText('未就绪').length).toBeGreaterThanOrEqual(5)
  })

  it('does not require Offer or Journey for invitation campaigns', () => {
    render(<CampaignCreateReadiness designIntent="REFERRAL" />)
    expect(screen.getByText(/REFERRAL_RELEASE_NOT_AVAILABLE/)).toBeInTheDocument()
    expect(screen.queryByText(/Offer：该活动已有决策定义/)).not.toBeInTheDocument()
    expect(screen.getAllByText('未就绪').length).toBeGreaterThanOrEqual(5)
  })

  it('does not treat another campaign release as ready', () => {
    const required = [definition('offer-campaign-42', 3), definition('journey-campaign-42', 2)]
    expect(definitionsHaveRelease(required, [release('offer-other-campaign', 3), release('journey-other-campaign', 2)])).toBe(false)
    expect(definitionsHaveRelease(required, [release('offer-campaign-42', 3), release('journey-campaign-42', 2)])).toBe(true)
  })
})
