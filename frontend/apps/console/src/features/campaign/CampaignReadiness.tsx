import { definitionsHaveRelease } from './campaignReleaseReadiness'
import { useQuery } from '@tanstack/react-query'
import { Badge, Button, EmptyState } from '../../components/ui'
import { api, optionalResource } from '../../shared/api/client'
import { releaseScopeKey, useReleasePages } from '../../shared/api/useReleasePages'
import { problemDetail } from '../../shared/api/problem'
import { useAuth } from '../../shared/auth/useAuth'
import { benefitReferences, offerDiscountNodes } from '../../shared/model/offerGraphReferences'
import { CAMPAIGN_TYPE, isKnownCampaignType } from '../../shared/model/campaignType'
import { REFERRAL_POLICY_DIALECT } from '../../shared/api/schemas'
import { fromReferralGraph, rewardsHaveCatalogPins } from '../referral/referralGraph'

const CREATE_HINTS = [
  '权益：按活动引用的固定版本和 SKU 核对',
  '人群：如限定参与范围，需绑定并核验人群快照',
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
      <p>{designIntent === 'REFERRAL' ? '邀请有礼创建后先保存规则。当前版本暂不支持发布。' : '创建后，从活动中核对配置与发布清单。'}</p>
      <ul>
        {hints.map((item) => (
          <li key={item}><Badge tone="warn">未就绪</Badge>{item}</li>
        ))}
      </ul>
    </div>
  )
}


const STATUS = { READY: '就绪', MISSING: '缺配置', UNKNOWN: '待核实', NOT_APPLICABLE: '不适用' } as const

export function CampaignReadiness({ campaignId, campaignType }: { campaignId: string; campaignType?: string }) {
  const auth = useAuth()
  const scope = releaseScopeKey(auth)
  const live = !api.demoMode && auth.hasPermission('campaign:read')
  const canReadDefinitions = live && auth.hasPermission('definition:read')
  const standard = campaignType === CAMPAIGN_TYPE.STANDARD
  const isReferral = campaignType === CAMPAIGN_TYPE.REFERRAL
  const offer = useQuery({
    queryKey: ['campaign-readiness-definition', scope, campaignId, 'OFFER_DECISION_DAG'],
    queryFn: async () => (await optionalResource(() => api.latestDefinition(campaignId, 'OFFER_DECISION_DAG'))) ?? null,
    enabled: canReadDefinitions && standard,
  })
  const journey = useQuery({
    queryKey: ['campaign-readiness-definition', scope, campaignId, 'JOURNEY_STATE_MACHINE'],
    queryFn: async () => (await optionalResource(() => api.latestDefinition(campaignId, 'JOURNEY_STATE_MACHINE'))) ?? null,
    enabled: canReadDefinitions && standard,
  })
  const referral = useQuery({
    queryKey: ['campaign-readiness-definition', scope, campaignId, REFERRAL_POLICY_DIALECT],
    queryFn: async () => (await optionalResource(() => api.latestDefinition(campaignId, REFERRAL_POLICY_DIALECT))) ?? null,
    enabled: canReadDefinitions && isReferral,
  })
  const graph = offer.isSuccess ? offer.data?.graph : undefined
  const discountNodes = offerDiscountNodes(graph)
  const references = benefitReferences(graph)
  const snapshots = [...new Set(discountNodes.map(node => node.config.requiredAudienceSnapshotId?.trim()).filter(Boolean))]
  const readCatalog = live && standard && auth.hasPermission('benefit:read') && references.length > 0
  const benefits = useQuery({ queryKey: ['campaign-readiness-benefits', scope], queryFn: api.benefits, enabled: readCatalog })
  const skus = useQuery({ queryKey: ['campaign-readiness-skus', scope], queryFn: () => api.benefitSkus('ACTIVE'), enabled: readCatalog })
  const releases = useReleasePages(undefined, live && standard)

  if (!live || !isKnownCampaignType(campaignType)) {
    return <EmptyState title="活动就绪状态待核实" detail={api.demoMode ? '连接营销服务后可核对活动配置。' : '需要确认活动类型和读取权限。'} />
  }

  const offerState = !offer.isSuccess ? STATUS.UNKNOWN : offer.data ? STATUS.READY : STATUS.MISSING
  const journeyState = !journey.isSuccess ? STATUS.UNKNOWN : journey.data ? STATUS.READY : STATUS.MISSING
  const hasGrant = journey.isSuccess && journey.data?.graph?.nodes.some(node => node.stableTypeId === 'journey.grant')
  let benefit = { label: '权益（待核对本活动引用）', status: STATUS.UNKNOWN as typeof STATUS[keyof typeof STATUS] }
  if (graph && discountNodes.some(node => !node.config.benefitDefinitionVersion?.trim())) {
    benefit = { label: '权益（有优惠节点未绑定权益版本）', status: STATUS.MISSING }
  } else if (references.length > 0 && benefits.isSuccess && skus.isSuccess && readCatalog) {
    const activeSkuIds = new Set(skus.data.filter(item => item.status === 'ACTIVE').map(item => item.skuId))
    const matched = references.map(reference => benefits.data.find(item => `${item.benefitId}@${item.version}` === reference))
    if (matched.some(item => item && (item.status !== 'ACTIVE' || !item.benefitSkuId || !activeSkuIds.has(item.benefitSkuId)))) {
      benefit = { label: `权益（引用的版本或 SKU 不可用：${references.join('、')}）`, status: STATUS.MISSING }
    } else if (matched.every(Boolean) && !hasGrant && journey.isSuccess && (!journey.data || journey.data.graph)) {
      benefit = { label: `权益（已核对活动引用：${references.join('、')}）`, status: STATUS.READY }
    }
  } else if (offer.isSuccess && offer.data === null && journey.isSuccess && journey.data?.graph && !hasGrant) {
    benefit = { label: '权益（当前旅程无资源发放）', status: STATUS.NOT_APPLICABLE }
  } else if (offer.isSuccess && !offer.data && journey.isSuccess && !journey.data) {
    benefit = { label: '权益（配置 Offer 或旅程后核对）', status: STATUS.MISSING }
  }
  const audience = snapshots.length > 0
    ? { label: `人群（快照 ${snapshots.join('、')}，在线资格尚待核验）`, status: STATUS.UNKNOWN }
    : graph || (offer.isSuccess && !offer.data && journey.isSuccess && journey.data?.graph)
      ? { label: '人群（当前规则未限制人群快照）', status: STATUS.NOT_APPLICABLE }
      : { label: '人群（待核对本活动的快照引用）', status: offerState === STATUS.MISSING ? STATUS.MISSING : STATUS.UNKNOWN }

  const releaseReady = definitionsHaveRelease([offer.data, journey.data], releases.items) && !releases.isError && offer.isSuccess && journey.isSuccess
  const releaseUnknown = !releases.canRead || releases.isPending || releases.isError || Boolean(releases.hasNextPage) || !offer.isSuccess || !journey.isSuccess
  const releaseLabel = !releases.canRead ? '发布（缺少 release:read）'
    : releaseReady ? '发布（已发现对应版本的暂存或激活清单）'
      : releaseUnknown ? '发布（尚未核实完整清单）' : '发布（未发现对应版本的暂存或激活清单）'
  const referralGraph = referral.isSuccess && referral.data?.graph ? fromReferralGraph(referral.data.graph) : undefined
  const items = isReferral
    ? [
        { label: '裂变规则', status: !referral.isSuccess ? STATUS.UNKNOWN : referral.data ? STATUS.READY : STATUS.MISSING },
        { label: '裂变奖励（权益及 SKU 版本引用）', status: !referralGraph ? STATUS.UNKNOWN : referralGraph.errors.length === 0 && rewardsHaveCatalogPins(referralGraph.draft) ? STATUS.READY : STATUS.MISSING },
        { label: '裂变发布（当前版本暂不支持）', status: STATUS.NOT_APPLICABLE },
      ]
    : [
        benefit,
        audience,
        { label: 'Offer 规则', status: offerState },
        { label: 'Journey 旅程', status: journeyState },
        { label: releaseLabel, status: releaseReady ? STATUS.READY : releaseUnknown ? STATUS.UNKNOWN : STATUS.MISSING },
      ]
  const loadError = [offer, journey, referral, benefits, skus, releases].find(query => query.isError)

  return (
    <div className="campaign-readiness" aria-label={`${campaignId} 就绪清单`}>
      {loadError?.error && <p>{problemDetail(loadError.error)}</p>}
      <ul>
        {items.map(item => <li key={item.label}><Badge tone={item.status === STATUS.READY ? 'good' : item.status === STATUS.NOT_APPLICABLE ? 'neutral' : 'warn'}>{item.status}</Badge>{item.label}</li>)}
      </ul>
      {standard && releases.canRead && <div className="action-bar toolbar">
        <span>已核对 {releases.items.length} 条发布清单</span>
        {releases.hasNextPage && <Button disabled={releases.isFetching} onClick={() => void releases.fetchNextPage()}>{releases.isFetchNextPageError ? '重试后续发布清单' : '继续核对发布清单'}</Button>}
        <Button disabled={releases.isFetching} onClick={() => void releases.refresh()}>重新核对发布清单</Button>
      </div>}
    </div>
  )
}
