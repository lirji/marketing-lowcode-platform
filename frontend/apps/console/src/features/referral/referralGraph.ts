import { REFERRAL_POLICY_DIALECT, type GraphDefinition } from '../../shared/api/schemas'
import { parseCurrencyCode, parseReferralInstant, parseReferralInteger } from './referralIntegers'

export const REFERRAL_NODE_ORDER = ['referral.start', 'referral.bind', 'referral.qualify', 'referral.reward', 'referral.end'] as const

const START_FIELDS = ['startsAt', 'endsAt', 'settlementEndsAt', 'organizationId', 'shopId'] as const
const BIND_FIELDS = ['attribution', 'maxBindAgeSeconds', 'inviteeScope'] as const
const QUALIFY_FIELDS = ['goalType', 'qualificationWindowSeconds', 'minNetAmountMinor', 'currency', 'observationSeconds', 'lateArrivalGraceSeconds'] as const
const REWARD_FIELDS = ['ruleId', 'role', 'mode', 'threshold', 'benefitDefinitionVersion', 'skuVersion', 'quantity', 'perSubjectLimit', 'campaignLimit'] as const

export const REFERRAL_ATTRIBUTION = 'FIRST_VALID_BIND'
export const REFERRAL_INVITEE_SCOPE = 'NEW_CUSTOMER'
export const REFERRAL_GOAL_TYPES = ['REGISTERED_NEW_CUSTOMER', 'FIRST_ORDER_SETTLED'] as const
export const REFERRAL_ROLES = ['INVITER', 'INVITEE'] as const
export const REFERRAL_MODES = ['PER_RELATION', 'MILESTONE'] as const

export type ReferralGoalType = (typeof REFERRAL_GOAL_TYPES)[number]
export type ReferralRole = (typeof REFERRAL_ROLES)[number]
export type ReferralMode = (typeof REFERRAL_MODES)[number]

export type ReferralRewardDraft = {
  key: string
  ruleId: string
  role: ReferralRole
  mode: ReferralMode
  threshold: string
  benefitDefinitionVersion: string
  skuVersion: string
  perSubjectLimit: string
  campaignLimit: string
}

export type ReferralDraft = {
  startsAt: string
  endsAt: string
  settlementEndsAt: string
  organizationId: string
  shopId: string
  maxBindAgeSeconds: string
  goalType: ReferralGoalType | ''
  qualificationWindowSeconds: string
  minNetAmountMinor: string
  currency: string
  observationSeconds: string
  lateArrivalGraceSeconds: string
  rewards: ReferralRewardDraft[]
}

export type FieldError = { field: string; section: string; message: string }

export function emptyReferralDraft(scope?: { organizationId?: string; shopId?: string }): ReferralDraft {
  return {
    startsAt: '',
    endsAt: '',
    settlementEndsAt: '',
    organizationId: scope?.organizationId ?? '',
    shopId: scope?.shopId ?? '',
    maxBindAgeSeconds: '',
    goalType: '',
    qualificationWindowSeconds: '',
    minNetAmountMinor: '',
    currency: '',
    observationSeconds: '',
    lateArrivalGraceSeconds: '',
    rewards: [],
  }
}

export function emptyReward(partial?: Partial<ReferralRewardDraft>): ReferralRewardDraft {
  return {
    key: partial?.key ?? `reward-${crypto.randomUUID()}`,
    ruleId: partial?.ruleId ?? '',
    role: partial?.role ?? 'INVITER',
    mode: partial?.mode ?? 'PER_RELATION',
    threshold: partial?.threshold ?? (partial?.mode === 'MILESTONE' ? '' : '1'),
    benefitDefinitionVersion: partial?.benefitDefinitionVersion ?? '',
    skuVersion: partial?.skuVersion ?? '',
    perSubjectLimit: partial?.perSubjectLimit ?? '',
    campaignLimit: partial?.campaignLimit ?? '',
  }
}

export function pinCatalogVersion(id: string, version: number | string): string {
  return `${id}@${version}`
}

export function splitCatalogVersion(value: string): { id: string; version: string } | undefined {
  const at = value.lastIndexOf('@')
  if (at <= 0 || at === value.length - 1) return undefined
  return { id: value.slice(0, at), version: value.slice(at + 1) }
}

function requireKeys(config: Record<string, string>, fields: readonly string[], nodeId: string): FieldError[] {
  const keys = Object.keys(config)
  const extra = keys.filter((key) => !fields.includes(key))
  const missing = fields.filter((key) => !(key in config))
  const errors: FieldError[] = []
  if (extra.length > 0) errors.push({ field: nodeId, section: nodeId, message: `多余字段：${extra.join(', ')}` })
  if (missing.length > 0) errors.push({ field: nodeId, section: nodeId, message: `缺少字段：${missing.join(', ')}` })
  return errors
}

function validateReward(reward: ReferralRewardDraft, index: number): FieldError[] {
  const prefix = `rewards.${index}`
  const section = reward.mode === 'MILESTONE' ? '阶梯奖励' : reward.role === 'INVITEE' ? '被邀请人奖' : '邀请人逐人奖'
  const errors: FieldError[] = []
  if (!reward.ruleId.trim()) errors.push({ field: `${prefix}.ruleId`, section, message: '奖励 ID 不能为空' })
  if (!REFERRAL_ROLES.includes(reward.role)) errors.push({ field: `${prefix}.role`, section, message: '角色不支持' })
  if (!REFERRAL_MODES.includes(reward.mode)) errors.push({ field: `${prefix}.mode`, section, message: '模式不支持' })
  const threshold = parseReferralInteger(reward.threshold, { min: 1n })
  if (!threshold.ok) errors.push({ field: `${prefix}.threshold`, section, message: threshold.error })
  else if (reward.mode === 'PER_RELATION' && reward.threshold !== '1') {
    errors.push({ field: `${prefix}.threshold`, section, message: '逐人奖励门槛固定 1' })
  }
  if (reward.mode === 'MILESTONE' && reward.role !== 'INVITER') {
    errors.push({ field: `${prefix}.role`, section, message: '人数阶梯仅属于邀请人' })
  }
  if (!reward.benefitDefinitionVersion.trim()) {
    errors.push({ field: `${prefix}.benefitDefinitionVersion`, section, message: '必须引用实际权益定义版本，不能留空或自动选券' })
  }
  if (!reward.skuVersion.trim()) {
    errors.push({ field: `${prefix}.skuVersion`, section, message: '必须引用实际 SKU 版本，不能留空或自动选券' })
  }
  const subject = parseReferralInteger(reward.perSubjectLimit, { min: 1n })
  if (!subject.ok) errors.push({ field: `${prefix}.perSubjectLimit`, section, message: subject.error })
  const campaign = parseReferralInteger(reward.campaignLimit, { min: 1n })
  if (!campaign.ok) errors.push({ field: `${prefix}.campaignLimit`, section, message: campaign.error })
  if (subject.ok && campaign.ok && BigInt(campaign.wire) < BigInt(subject.wire)) {
    errors.push({ field: `${prefix}.campaignLimit`, section, message: '活动封顶必须大于等于个人封顶' })
  }
  return errors
}

export function validateReferralDraft(draft: ReferralDraft): FieldError[] {
  const errors: FieldError[] = []
  const startsAt = parseReferralInstant(draft.startsAt)
  const endsAt = parseReferralInstant(draft.endsAt)
  const settlement = parseReferralInstant(draft.settlementEndsAt)
  if (!startsAt.ok) errors.push({ field: 'startsAt', section: '基本信息', message: startsAt.error })
  if (!endsAt.ok) errors.push({ field: 'endsAt', section: '基本信息', message: endsAt.error })
  if (!settlement.ok) errors.push({ field: 'settlementEndsAt', section: '基本信息', message: settlement.error })
  if (startsAt.ok && endsAt.ok && Date.parse(draft.startsAt) >= Date.parse(draft.endsAt)) {
    errors.push({ field: 'endsAt', section: '基本信息', message: '结束时间必须晚于开始时间' })
  }
  if (endsAt.ok && settlement.ok && Date.parse(draft.settlementEndsAt) < Date.parse(draft.endsAt)) {
    errors.push({ field: 'settlementEndsAt', section: '基本信息', message: '结算截止不能早于活动结束' })
  }
  if (!draft.organizationId.trim()) errors.push({ field: 'organizationId', section: '基本信息', message: '组织不能为空' })
  if (!draft.shopId.trim()) errors.push({ field: 'shopId', section: '基本信息', message: '裂变图要求非空 shopId，不能提交空的全部店铺' })
  const bindAge = parseReferralInteger(draft.maxBindAgeSeconds, { min: 1n })
  if (!bindAge.ok) errors.push({ field: 'maxBindAgeSeconds', section: '参与及绑定', message: bindAge.error })
  if (!draft.goalType || !REFERRAL_GOAL_TYPES.includes(draft.goalType)) {
    errors.push({ field: 'goalType', section: '新客或首单', message: '请选择资格模式' })
  }
  const window = parseReferralInteger(draft.qualificationWindowSeconds, { min: 1n })
  if (!window.ok) errors.push({ field: 'qualificationWindowSeconds', section: '达标期限', message: window.error })
  const minNet = parseReferralInteger(draft.minNetAmountMinor, { min: 0n })
  if (!minNet.ok) errors.push({ field: 'minNetAmountMinor', section: '达标期限', message: minNet.error })
  const currency = parseCurrencyCode(draft.currency)
  if (!currency.ok) errors.push({ field: 'currency', section: '达标期限', message: currency.error })
  const observation = parseReferralInteger(draft.observationSeconds, { min: 0n })
  if (!observation.ok) errors.push({ field: 'observationSeconds', section: '达标期限', message: observation.error })
  const grace = parseReferralInteger(draft.lateArrivalGraceSeconds, { min: 0n })
  if (!grace.ok) errors.push({ field: 'lateArrivalGraceSeconds', section: '达标期限', message: grace.error })
  if (draft.rewards.length === 0) errors.push({ field: 'rewards', section: '奖励', message: '至少配置一个奖励' })
  const ids = new Set<string>()
  draft.rewards.forEach((reward, index) => {
    errors.push(...validateReward(reward, index))
    const id = reward.ruleId.trim()
    if (id && !ids.add(id)) errors.push({ field: `rewards.${index}.ruleId`, section: '奖励', message: '奖励 ID 不能重复' })
  })
  return errors
}

function rewardConfig(reward: ReferralRewardDraft): Record<string, string> {
  return {
    ruleId: reward.ruleId.trim(),
    role: reward.role,
    mode: reward.mode,
    threshold: reward.mode === 'PER_RELATION' ? '1' : reward.threshold,
    benefitDefinitionVersion: reward.benefitDefinitionVersion.trim(),
    skuVersion: reward.skuVersion.trim(),
    quantity: '1',
    perSubjectLimit: reward.perSubjectLimit,
    campaignLimit: reward.campaignLimit,
  }
}

export function toReferralGraph(input: { definitionId: string; draft: ReferralDraft; title?: string }): GraphDefinition {
  const errors = validateReferralDraft(input.draft)
  if (errors.length > 0) {
    throw new Error(errors.map((item) => `${item.section}：${item.message}`).join('；'))
  }
  const draft = input.draft
  const startId = 'referral-start'
  const bindId = 'referral-bind'
  const qualifyId = 'referral-qualify'
  const endId = 'referral-end'
  const rewardNodes = draft.rewards.map((reward) => ({
    id: `referral-reward-${reward.ruleId.trim()}`,
    stableTypeId: 'referral.reward',
    semanticVersion: '1.0.0',
    config: rewardConfig(reward),
  }))
  const nodes = [
    {
      id: startId,
      stableTypeId: 'referral.start',
      semanticVersion: '1.0.0',
      config: {
        startsAt: draft.startsAt,
        endsAt: draft.endsAt,
        settlementEndsAt: draft.settlementEndsAt,
        organizationId: draft.organizationId.trim(),
        shopId: draft.shopId.trim(),
      },
    },
    {
      id: bindId,
      stableTypeId: 'referral.bind',
      semanticVersion: '1.0.0',
      config: {
        attribution: REFERRAL_ATTRIBUTION,
        maxBindAgeSeconds: draft.maxBindAgeSeconds,
        inviteeScope: REFERRAL_INVITEE_SCOPE,
      },
    },
    {
      id: qualifyId,
      stableTypeId: 'referral.qualify',
      semanticVersion: '1.0.0',
      config: {
        goalType: draft.goalType,
        qualificationWindowSeconds: draft.qualificationWindowSeconds,
        minNetAmountMinor: draft.minNetAmountMinor,
        currency: draft.currency,
        observationSeconds: draft.observationSeconds,
        lateArrivalGraceSeconds: draft.lateArrivalGraceSeconds,
      },
    },
    ...rewardNodes,
    { id: endId, stableTypeId: 'referral.end', semanticVersion: '1.0.0', config: {} },
  ]
  const chain = [startId, bindId, qualifyId, ...rewardNodes.map((node) => node.id), endId]
  const edges = chain.slice(0, -1).map((source, index) => ({
    id: `referral-edge-${index}`,
    sourceNodeId: source,
    sourcePort: 'next',
    targetNodeId: chain[index + 1] ?? endId,
    targetPort: 'in',
  }))
  return {
    definitionId: input.definitionId,
    dialect: REFERRAL_POLICY_DIALECT,
    dialectVersion: '1.0.0',
    nodes,
    edges,
    variables: {},
    annotations: input.title ? { title: input.title } : {},
  }
}

function configOf(graph: GraphDefinition, type: string): Record<string, string> | undefined {
  return graph.nodes.find((node) => node.stableTypeId === type)?.config
}

export function fromReferralGraph(graph: GraphDefinition): { draft: ReferralDraft; errors: FieldError[] } {
  const errors: FieldError[] = []
  if (graph.dialect !== REFERRAL_POLICY_DIALECT) {
    errors.push({ field: 'dialect', section: '基本信息', message: `方言必须是 ${REFERRAL_POLICY_DIALECT}` })
  }
  if (graph.dialectVersion !== '1.0.0') {
    errors.push({ field: 'dialectVersion', section: '基本信息', message: '方言版本不受支持' })
  }
  if (Object.keys(graph.variables).length > 0) {
    errors.push({ field: 'variables', section: '基本信息', message: '裂变图禁止 variables' })
  }
  const start = configOf(graph, 'referral.start') ?? {}
  const bind = configOf(graph, 'referral.bind') ?? {}
  const qualify = configOf(graph, 'referral.qualify') ?? {}
  errors.push(...requireKeys(start, START_FIELDS, 'referral.start'))
  errors.push(...requireKeys(bind, BIND_FIELDS, 'referral.bind'))
  errors.push(...requireKeys(qualify, QUALIFY_FIELDS, 'referral.qualify'))
  if (bind.attribution && bind.attribution !== REFERRAL_ATTRIBUTION) {
    errors.push({ field: 'attribution', section: '参与及绑定', message: `归因算法仅支持 ${REFERRAL_ATTRIBUTION}` })
  }
  if (bind.inviteeScope && bind.inviteeScope !== REFERRAL_INVITEE_SCOPE) {
    errors.push({ field: 'inviteeScope', section: '参与及绑定', message: `被邀请人范围仅支持 ${REFERRAL_INVITEE_SCOPE}` })
  }
  const rewards = graph.nodes.filter((node) => node.stableTypeId === 'referral.reward').map((node, index) => {
    errors.push(...requireKeys(node.config, REWARD_FIELDS, node.id))
    const role = REFERRAL_ROLES.includes(node.config.role as ReferralRole) ? node.config.role as ReferralRole : 'INVITER'
    const mode = REFERRAL_MODES.includes(node.config.mode as ReferralMode) ? node.config.mode as ReferralMode : 'PER_RELATION'
    if (node.config.role && node.config.role !== role) {
      errors.push({ field: `rewards.${index}.role`, section: '奖励', message: `未知角色 ${node.config.role}` })
    }
    if (node.config.mode && node.config.mode !== mode) {
      errors.push({ field: `rewards.${index}.mode`, section: '奖励', message: `未知模式 ${node.config.mode}` })
    }
    return emptyReward({
      key: node.id,
      ruleId: node.config.ruleId ?? '',
      role,
      mode,
      threshold: node.config.threshold ?? '',
      benefitDefinitionVersion: node.config.benefitDefinitionVersion ?? '',
      skuVersion: node.config.skuVersion ?? '',
      perSubjectLimit: node.config.perSubjectLimit ?? '',
      campaignLimit: node.config.campaignLimit ?? '',
    })
  })
  const draft: ReferralDraft = {
    startsAt: start.startsAt ?? '',
    endsAt: start.endsAt ?? '',
    settlementEndsAt: start.settlementEndsAt ?? '',
    organizationId: start.organizationId ?? '',
    shopId: start.shopId ?? '',
    maxBindAgeSeconds: bind.maxBindAgeSeconds ?? '',
    goalType: REFERRAL_GOAL_TYPES.includes(qualify.goalType as ReferralGoalType) ? qualify.goalType as ReferralGoalType : '',
    qualificationWindowSeconds: qualify.qualificationWindowSeconds ?? '',
    minNetAmountMinor: qualify.minNetAmountMinor ?? '',
    currency: qualify.currency ?? '',
    observationSeconds: qualify.observationSeconds ?? '',
    lateArrivalGraceSeconds: qualify.lateArrivalGraceSeconds ?? '',
    rewards,
  }
  if (qualify.goalType && !draft.goalType) {
    errors.push({ field: 'goalType', section: '新客或首单', message: `未知资格模式 ${qualify.goalType}` })
  }
  return { draft, errors }
}

export function rewardsHaveCatalogPins(draft: ReferralDraft): boolean {
  return draft.rewards.length > 0 && draft.rewards.every((item) => item.benefitDefinitionVersion.trim() && item.skuVersion.trim())
}
