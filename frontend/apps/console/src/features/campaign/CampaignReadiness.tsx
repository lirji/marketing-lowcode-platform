import { useQuery } from '@tanstack/react-query'
import { Badge, EmptyState } from '../../components/ui'
import { api, optionalResource } from '../../shared/api/client'
import { problemDetail } from '../../shared/api/problem'
import { useAuth } from '../../shared/auth/useAuth'
import { REFERRAL_POLICY_DIALECT, type DefinitionBundle, type ReleaseView } from '../../shared/api/schemas'
import { fromReferralGraph, rewardsHaveCatalogPins } from '../referral/referralGraph'

const CREATE_HINTS = [
  '权益：已绑定 ACTIVE SKU',
  '人群：至少一条 ACTIVE 分段',
  'Offer：该活动已有决策定义',
  'Journey：该活动已有旅程定义',
  '发布：在发布中心编译并暂存本活动定义',
]

const REFERRAL_CREATE_HINTS = [
  '裂变规则：保存 REFERRAL_POLICY 定义',
  '奖励：引用真实权益定义与 SKU 版本',
  '目录核验：控制面 WARNING，前端不代核',
  '身份 / 事件源 / runtime：待后端就绪合同',
  '发布：REFERRAL_RELEASE_NOT_AVAILABLE',
]

export function CampaignCreateReadiness({ designIntent = 'STANDARD' }: { designIntent?: 'STANDARD' | 'REFERRAL' }) {
  const hints = designIntent === 'REFERRAL' ? REFERRAL_CREATE_HINTS : CREATE_HINTS
  return (
    <div className="campaign-readiness" aria-label="活动就绪清单">
      <p>{designIntent === 'REFERRAL' ? '邀请有礼创建后先保存规则。发布链未接通，不会用假数据顶上。' : '创建后还要齐这些项才能发布。缺项会标未就绪，不会用假数据顶上。'}</p>
      <ul>
        {hints.map((item) => (
          <li key={item}><Badge tone="warn">未就绪</Badge>{item}</li>
        ))}
      </ul>
    </div>
  )
}

/**
 * 只接受制品来源与当前活动冻结版本完全一致的 Release，避免被同租户其他活动的发布误判为就绪。
 */
export function definitionsHaveRelease(definitions: Array<DefinitionBundle | undefined>, releases: ReleaseView[]): boolean {
  const required = definitions.filter((item): item is DefinitionBundle => Boolean(item))
  return required.length === definitions.length && required.every((definition) => releases.some((release) =>
    (release.state === 'STAGED' || release.state === 'ACTIVE')
    && release.manifest.artifacts.some((artifact) => artifact.definitionId === definition.definitionId && artifact.definitionVersion === definition.version),
  ))
}

export function CampaignReadiness({ campaignId }: { campaignId: string }) {
  const auth = useAuth()
  const canRead = auth.hasPermission('campaign:read')
  const benefits = useQuery({
    queryKey: ['benefits'],
    queryFn: api.benefits,
    enabled: !api.demoMode && canRead && auth.hasPermission('benefit:read'),
  })
  const skus = useQuery({
    queryKey: ['benefit-skus', 'ACTIVE'],
    queryFn: () => api.benefitSkus('ACTIVE'),
    enabled: !api.demoMode && canRead && auth.hasPermission('benefit:read'),
  })
  const audiences = useQuery({
    queryKey: ['audiences'],
    queryFn: api.audiences,
    enabled: !api.demoMode && canRead,
  })
  const offer = useQuery({
    queryKey: ['definition-latest', campaignId, 'OFFER_DECISION_DAG'],
    queryFn: () => optionalResource(() => api.latestDefinition(campaignId, 'OFFER_DECISION_DAG')),
    enabled: !api.demoMode && canRead && Boolean(campaignId),
  })
  const journey = useQuery({
    queryKey: ['definition-latest', campaignId, 'JOURNEY_STATE_MACHINE'],
    queryFn: () => optionalResource(() => api.latestDefinition(campaignId, 'JOURNEY_STATE_MACHINE')),
    enabled: !api.demoMode && canRead && Boolean(campaignId),
  })
  const referral = useQuery({
    queryKey: ['definition-latest', campaignId, REFERRAL_POLICY_DIALECT],
    queryFn: () => optionalResource(() => api.latestDefinition(campaignId, REFERRAL_POLICY_DIALECT)),
    enabled: !api.demoMode && canRead && Boolean(campaignId),
  })
  const releases = useQuery({
    queryKey: ['releases'],
    queryFn: api.releases,
    enabled: !api.demoMode && canRead,
  })

  if (api.demoMode) {
    return <EmptyState title="演示模式不查就绪" detail="关闭 DEMO_MODE 后按控制面接口核对权益 / 人群 / Offer / Journey / 发布。" />
  }

  const activeSkuIds = new Set((skus.data ?? []).map((item) => item.skuId))
  const benefitReady = (benefits.data ?? []).some((item) => item.benefitSkuId && activeSkuIds.has(item.benefitSkuId))
  const audienceReady = (audiences.data ?? []).some((item) => item.status === 'ACTIVE')
  const offerReady = Boolean(offer.data)
  const journeyReady = Boolean(journey.data)
  const releaseReady = definitionsHaveRelease([offer.data, journey.data], releases.data ?? [])
  const referralGraph = referral.data?.graph ? fromReferralGraph(referral.data.graph) : undefined
  const referralReady = Boolean(referral.data)
  const referralPinsReady = Boolean(referralGraph && referralGraph.errors.length === 0 && rewardsHaveCatalogPins(referralGraph.draft))
  const items = referralReady
    ? [
        { key: 'referral-rule', label: '裂变规则（REFERRAL_POLICY 已保存）', ready: true },
        { key: 'referral-pins', label: '奖励已引用权益 / SKU 版本', ready: referralPinsReady },
        { key: 'referral-catalog', label: '真实目录核验（控制面 WARNING，非前端代核）', ready: false },
        { key: 'referral-runtime', label: '身份 / 事件源 / runtime', ready: false },
        { key: 'referral-release', label: '发布（REFERRAL_RELEASE_NOT_AVAILABLE）', ready: false },
      ]
    : [
        { key: 'benefit', label: '权益（已绑 ACTIVE SKU）', ready: benefitReady },
        { key: 'audience', label: '人群', ready: audienceReady },
        { key: 'offer', label: 'Offer', ready: offerReady },
        { key: 'journey', label: 'Journey', ready: journeyReady },
        { key: 'release', label: releaseReady ? '发布' : '发布（未在发布中心编译）', ready: releaseReady },
      ]
  const loadError = [benefits, skus, audiences, offer, journey, referral, releases].find((q) => q.isError)

  return (
    <div className="campaign-readiness" aria-label={`${campaignId} 就绪清单`}>
      {loadError?.error && <p>{problemDetail(loadError.error)}</p>}
      <ul>
        {items.map((item) => (
          <li key={item.key}>
            <Badge tone={item.ready ? 'good' : 'warn'}>{item.ready ? '就绪' : '未就绪'}</Badge>
            {item.label}
          </li>
        ))}
      </ul>
    </div>
  )
}
