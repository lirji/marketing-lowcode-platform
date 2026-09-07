import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../../shared/auth/AuthContext'
import { ApiProblem } from '../../shared/api/client'
import type { ApprovalView, ArtifactView, BenefitSku, DefinitionBundle } from '../../shared/api/schemas'

const { state, mocks } = vi.hoisted(() => ({
  state: { demoMode: false },
  mocks: {
    approvals: vi.fn(), getDefinition: vi.fn(), benefits: vi.fn(), benefitSkus: vi.fn(), releases: vi.fn(),
    compile: vi.fn(), getArtifact: vi.fn(), stageRelease: vi.fn(), acknowledgeRelease: vi.fn(),
  },
}))

vi.mock('../../shared/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../shared/api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      get demoMode() { return state.demoMode },
      approvals: mocks.approvals,
      getDefinition: mocks.getDefinition,
      benefits: mocks.benefits,
      benefitSkus: mocks.benefitSkus,
      releases: mocks.releases,
      compile: mocks.compile,
      getArtifact: mocks.getArtifact,
      stageRelease: mocks.stageRelease,
      acknowledgeRelease: mocks.acknowledgeRelease,
    },
  }
})

import { ReleaseWizard } from './ReleaseWizard'

const approval: ApprovalView = {
  caseId: 'approval-1', definitionId: 'offer-campaign-1', definitionVersion: 3, submittedBy: 'operator',
  requiredRoles: ['BUSINESS'], approvals: { BUSINESS: 'APPROVE' }, status: 'APPROVED', updatedAt: '2026-09-06T00:00:00Z',
}

const bundle: DefinitionBundle = {
  definitionId: 'offer-campaign-1', campaignId: 'campaign-1', version: 3, dialect: 'OFFER_DECISION_DAG', status: 'APPROVED',
  graph: {
    definitionId: 'offer-campaign-1', dialect: 'OFFER_DECISION_DAG', dialectVersion: '1',
    nodes: [{ id: 'offer', stableTypeId: 'offer.fixed', semanticVersion: '1', config: { benefitDefinitionVersion: 'benefit-1@1' } }],
    edges: [], variables: {}, annotations: {},
  },
}

const artifact: ArtifactView = {
  artifactId: 'artifact-1', type: 'OFFER_POLICY', checksum: 'sha256:1', sourceDigest: 'sha256:source',
  signatureKeyId: 'compiler-key', signature: 'signature', abi: 'offer-policy-v1', definitionId: bundle.definitionId,
  definitionVersion: bundle.version, payload: '{}', compiledAt: '2026-09-06T00:00:00Z',
}

function renderWizard(onRequestActivate = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(<QueryClientProvider client={queryClient}><MemoryRouter><AuthProvider><ReleaseWizard open onClose={() => undefined} onRequestActivate={onRequestActivate} /></AuthProvider></MemoryRouter></QueryClientProvider>)
  return onRequestActivate
}

async function selectApprovedDefinition() {
  const user = userEvent.setup()
  await screen.findByRole('option', { name: /approval-1/ })
  await user.selectOptions(screen.getByLabelText('批准单 / 冻结版本'), approval.caseId)
  await screen.findByText(/OFFER_DECISION_DAG/)
  return user
}

describe('ReleaseWizard', () => {
  beforeEach(() => {
    state.demoMode = false
    Object.values(mocks).forEach((mock) => mock.mockReset())
    mocks.approvals.mockResolvedValue([approval])
    mocks.getDefinition.mockResolvedValue(bundle)
    mocks.benefits.mockResolvedValue([{ benefitId: 'benefit-1', version: 1, name: '券', status: 'ACTIVE', benefitSkuId: '1001', policy: {} }])
    mocks.benefitSkus.mockResolvedValue([{ skuId: '1001', benefitType: 'COUPON', status: 'ACTIVE', version: 1 } as BenefitSku])
    mocks.releases.mockResolvedValue([])
    mocks.getArtifact.mockResolvedValue(artifact)
  })

  it('stops after valid=false and never reads or stages an artifact', async () => {
    mocks.compile.mockResolvedValue({ valid: false, artifactId: null, checksum: null, signatureKeyId: null, signature: null, abi: null, messages: ['INVALID_GRAPH'] })
    renderWizard()
    const user = await selectApprovedDefinition()
    await user.click(screen.getByRole('button', { name: '编译' }))
    expect(await screen.findByText(/INVALID_GRAPH/)).toBeInTheDocument()
    expect(mocks.getArtifact).not.toHaveBeenCalled()
    expect(mocks.stageRelease).not.toHaveBeenCalled()
    expect(mocks.acknowledgeRelease).not.toHaveBeenCalled()
  })

  it('shows SKU_NOT_ACTIVE, keeps activation disabled, and never sends ACK', async () => {
    mocks.compile.mockResolvedValue({ valid: true, artifactId: artifact.artifactId, checksum: artifact.checksum, signatureKeyId: artifact.signatureKeyId, signature: artifact.signature, abi: artifact.abi, messages: [] })
    mocks.stageRelease.mockRejectedValue(new ApiProblem({ type: 'about:blank', title: 'Conflict', status: 409, detail: 'SKU is not ACTIVE', code: 'SKU_NOT_ACTIVE' }))
    const activate = renderWizard()
    const user = await selectApprovedDefinition()
    await user.click(screen.getByRole('button', { name: '编译' }))
    await screen.findByText('制品已读取')
    await waitFor(() => expect(screen.getByRole('button', { name: '暂存' })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: '暂存' }))
    expect(await screen.findByText(/SKU_NOT_ACTIVE/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '进入激活确认' })).toBeDisabled()
    expect(activate).not.toHaveBeenCalled()
    expect(mocks.acknowledgeRelease).not.toHaveBeenCalled()
  })
})
