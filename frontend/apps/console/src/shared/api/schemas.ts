import { z } from 'zod'
import type { ProblemDetail } from '@marketing/contracts'

export const problemDetailSchema: z.ZodType<ProblemDetail> = z.object({
  type: z.string().default('about:blank'),
  title: z.string().default('请求失败'),
  status: z.number().int(),
  detail: z.string().default('请求失败'),
  instance: z.string().optional(),
  code: z.string().optional(),
  retryable: z.boolean().optional(),
})

export const campaignSchema = z.object({
  id: z.string(),
  name: z.string(),
  objective: z.string(),
  status: z.enum(['DRAFT', 'IN_REVIEW', 'APPROVED', 'ACTIVE', 'PAUSED', 'ENDED']),
  organizationId: z.string().optional(),
  shopId: z.string().optional(),
  createdBy: z.string().optional(),
  createdAt: z.string(),
  updatedAt: z.string().optional(),
})
export type Campaign = z.infer<typeof campaignSchema>

export const validationIssueSchema = z.object({
  severity: z.string(),
  code: z.string(),
  pointer: z.string().optional().default(''),
  nodeId: z.string().nullable().optional(),
  message: z.string(),
})
export const validationResultSchema = z.object({
  valid: z.boolean(),
  semanticHash: z.string().optional(),
  issues: z.array(validationIssueSchema).default([]),
})
export type ValidationResult = z.infer<typeof validationResultSchema>

export const simulationSchema = z.object({
  subtotalMinor: z.number(),
  discountMinor: z.number(),
  payableMinor: z.number(),
  trace: z.array(z.object({
    nodeId: z.string(),
    nodeType: z.string(),
    outcome: z.string(),
  })).default([]),
})
export type SimulationResult = z.infer<typeof simulationSchema>

export const definitionViewSchema = z.object({
  definitionId: z.string(),
  campaignId: z.string(),
  version: z.number(),
  dialect: z.string(),
  semanticHash: z.string().optional(),
  status: z.string(),
  createdBy: z.string().optional(),
})
export type DefinitionView = z.infer<typeof definitionViewSchema>

export const approvalRoleSchema = z.enum(['BUSINESS', 'FINANCE', 'COMPLIANCE', 'MERCHANT'])
export type ApprovalRole = z.infer<typeof approvalRoleSchema>

export const approvalViewSchema = z.object({
  caseId: z.string(),
  definitionId: z.string(),
  definitionVersion: z.number(),
  submittedBy: z.string(),
  requiredRoles: z.array(approvalRoleSchema).default([]),
  approvals: z.record(z.string(), z.string()).default({}),
  status: z.enum(['OPEN', 'APPROVED', 'REJECTED']),
  updatedAt: z.string(),
})
export type ApprovalView = z.infer<typeof approvalViewSchema>

export const releaseViewSchema = z.object({
  manifest: z.object({
    manifestId: z.string(),
    environment: z.string().optional(),
    cell: z.string(),
    runtime: z.string().optional(),
    namespace: z.string().optional(),
    generation: z.number(),
    stableGeneration: z.number().optional(),
    canaryBasisPoints: z.number().optional(),
    artifacts: z.array(z.unknown()).default([]),
    signature: z.string().optional(),
    createdBy: z.string().optional(),
  }),
  state: z.string(),
  readyReplicas: z.number().optional().default(0),
  readyCapacity: z.number().optional().default(0),
})
export type ReleaseView = z.infer<typeof releaseViewSchema>

export const killSwitchViewSchema = z.object({
  namespace: z.string(),
  enabled: z.boolean(),
  reason: z.string(),
  updatedBy: z.string().optional(),
  sequence: z.number().optional(),
})

export const dashboardSchema = z.object({
  counts: z.record(z.string(), z.number()).default({}),
  attributedRevenueMinor: z.number().default(0),
  fundingCostMinor: z.number().default(0),
  roi: z.union([z.number(), z.string()]).optional(),
  completeThrough: z.string().optional(),
  lag: z.object({ seconds: z.number() }).optional(),
})
export type DashboardMetrics = z.infer<typeof dashboardSchema>

export const reconciliationSchema = z.object({
  balanced: z.boolean(),
  violations: z.array(z.string()).default([]),
  ledgerMovement: z.number().optional(),
  checkedAt: z.string().optional(),
})
export type ReconciliationReport = z.infer<typeof reconciliationSchema>

export const traceEventSchema = z.object({
  at: z.string().optional(),
  type: z.string(),
  title: z.string().optional(),
  detail: z.string().optional(),
})
export const traceViewSchema = z.object({
  traceId: z.string().optional(),
  requestId: z.string().optional(),
  orderId: z.string().nullable().optional(),
  maskedSubject: z.string().optional(),
  generation: z.number().optional(),
  durationMicros: z.number().optional(),
  candidates: z.unknown().optional(),
  pricing: z.unknown().optional(),
  termsVersion: z.string().nullable().optional(),
  legalHold: z.boolean().optional(),
  createdAt: z.string().optional(),
  events: z.array(traceEventSchema).optional().default([]),
})
export type TraceView = z.infer<typeof traceViewSchema>

export const enrollmentViewSchema = z.object({
  enrollmentId: z.string(),
  journeyId: z.string(),
  journeyVersion: z.number(),
  subjectToken: z.string().optional(),
  currentNodeId: z.string().optional(),
  status: z.string(),
  duplicate: z.boolean().optional(),
  projectedAt: z.string().optional(),
})
export type EnrollmentView = z.infer<typeof enrollmentViewSchema>

export const contactViewSchema = z.object({
  contactId: z.string(),
  contactKey: z.string(),
  state: z.string(),
  providerRequestId: z.string().optional(),
  providerCode: z.string().optional(),
  updatedAt: z.string().optional(),
})
export type ContactView = z.infer<typeof contactViewSchema>

export const quarantineViewSchema = z.object({
  quarantineId: z.string(),
  receiptId: z.string().optional(),
  reasonCode: z.string(),
  state: z.string(),
  createdAt: z.string().optional(),
  replayedAt: z.string().nullable().optional(),
})
export type QuarantineView = z.infer<typeof quarantineViewSchema>

export const nodeRegistrySchema = z.object({
  stableTypeId: z.string(),
  semanticVersion: z.string(),
  dialects: z.array(z.string()).default([]),
  runtimeTarget: z.string().optional(),
  costWeight: z.number().optional(),
  sideEffect: z.string().optional(),
})
export type NodeRegistryItem = z.infer<typeof nodeRegistrySchema>

export const graphDefinitionSchema = z.object({
  definitionId: z.string(),
  dialect: z.string(),
  dialectVersion: z.string(),
  nodes: z.array(z.object({
    id: z.string(),
    stableTypeId: z.string(),
    semanticVersion: z.string(),
    config: z.record(z.string(), z.string()).default({}),
  })),
  edges: z.array(z.object({
    id: z.string(),
    sourceNodeId: z.string(),
    sourcePort: z.string(),
    targetNodeId: z.string(),
    targetPort: z.string(),
  })),
  variables: z.record(z.string(), z.string()).default({}),
  annotations: z.record(z.string(), z.string()).default({}),
})
export type GraphDefinition = z.infer<typeof graphDefinitionSchema>

export const definitionBundleSchema = definitionViewSchema.extend({
  graph: graphDefinitionSchema.optional(),
  createdAt: z.string().optional(),
  updatedAt: z.string().optional(),
})
export type DefinitionBundle = z.infer<typeof definitionBundleSchema>

export const seriesPointSchema = z.object({
  at: z.string(),
  revenueMinor: z.number().default(0),
  costMinor: z.number().default(0),
  conversions: z.number().default(0),
})
export const seriesSchema = z.object({
  points: z.array(seriesPointSchema).default([]),
})
export type SeriesPoint = z.infer<typeof seriesPointSchema>

export const audienceConditionSchema = z.object({
  fieldId: z.string(),
  operator: z.string(),
  value: z.string(),
})
export const audienceViewSchema = z.object({
  segmentId: z.string(),
  version: z.number(),
  name: z.string(),
  status: z.string(),
  rule: z.object({
    match: z.string(),
    conditions: z.array(audienceConditionSchema).default([]),
  }).optional(),
  ruleHash: z.string().optional(),
  createdAt: z.string().optional(),
})
export type AudienceView = z.infer<typeof audienceViewSchema>

export const audiencePreviewSchema = z.object({
  estimatedCount: z.number(),
  sampleSubjectTokens: z.array(z.string()).default([]),
  calculatedAt: z.string().optional(),
})

export const audienceSnapshotSchema = z.object({
  snapshotId: z.string(),
  segmentId: z.string(),
  segmentVersion: z.number().optional(),
  memberCount: z.number().optional(),
  checksum: z.string().optional(),
  state: z.string().optional(),
})

export const fieldDefinitionSchema = z.object({
  fieldId: z.string(),
  valueType: z.string().optional(),
  owner: z.string().optional(),
  provenance: z.string().optional(),
  classification: z.string().optional(),
  maxAgeSeconds: z.number().optional(),
})
export type FieldDefinition = z.infer<typeof fieldDefinitionSchema>

export const templateViewSchema = z.object({
  templateId: z.string(),
  version: z.number(),
  channel: z.string(),
  content: z.string().optional(),
  requiredVariables: z.array(z.string()).default([]),
  state: z.string().optional(),
  createdAt: z.string().optional(),
})
export type TemplateView = z.infer<typeof templateViewSchema>

export const benefitSkuStatusSchema = z.enum(['DRAFT', 'PENDING_APPROVAL', 'ACTIVE', 'PAUSED', 'RETIRED'])
export const benefitSkuSchema = z.object({
  skuId: z.string(),
  benefitType: z.enum(['COUPON', 'CASH', 'CODE', 'PHYSICAL']),
  faceValueMinor: z.number().nullable().optional(),
  currency: z.string().nullable().optional(),
  status: benefitSkuStatusSchema,
  enabled: z.boolean(),
  validityType: z.enum(['ABSOLUTE', 'RELATIVE']),
  validFrom: z.string().nullable().optional(),
  validTo: z.string().nullable().optional(),
  relativeDays: z.number().nullable().optional(),
  usableWeekdays: z.array(z.number()).default([]),
  dailyQuota: z.number().nullable().optional(),
  userLimitPerDay: z.number().nullable().optional(),
  userLimitTotal: z.number().nullable().optional(),
  equivalentSkuId: z.string().nullable().optional(),
  version: z.number(),
})
export type BenefitSku = z.infer<typeof benefitSkuSchema>

export const awardIntentViewSchema = z.object({
  intentId: z.string(),
  sourceSystem: z.string(),
  sourceRequestId: z.string(),
  campaignId: z.string(),
  definitionVersion: z.number(),
  subjectHash: z.string(),
  deliveryMode: z.enum(['LEGACY', 'SHADOW', 'CENTER']),
  status: z.enum(['PENDING', 'SENT', 'DEAD', 'RISK_BLOCKED']),
  deliveryResult: z.enum(['LEGACY_OWNED', 'SHADOW_RECORDED', 'CENTER_ENQUEUED', 'CENTER_ACCEPTED']).nullable(),
  riskAction: z.enum(['CHALLENGE', 'REVIEW', 'REJECT', 'UNAVAILABLE']).optional(),
  riskReason: z.string().optional(),
  riskDecisionId: z.string().nullable().optional(),
  attempts: z.number(),
  benefitOrderNo: z.string().nullable().optional(),
  lastError: z.string().optional().default(''),
  createdAt: z.string(),
  updatedAt: z.string(),
  sentAt: z.string().nullable().optional(),
})
export type AwardIntentView = z.infer<typeof awardIntentViewSchema>

export const benefitViewSchema = z.object({
  benefitId: z.string(),
  version: z.number(),
  name: z.string(),
  status: z.string(),
  resourceKey: z.string().optional(),
  benefitSkuId: z.string().nullable().optional(),
  policy: z.record(z.string(), z.unknown()).default({}),
  createdBy: z.string().optional(),
  createdAt: z.string().optional(),
})
export type BenefitView = z.infer<typeof benefitViewSchema>

export const accountViewSchema = z.object({
  resourceKey: z.string(),
  type: z.string().optional(),
  currency: z.string().optional(),
  authorized: z.number().default(0),
  available: z.number().default(0),
  reserved: z.number().default(0),
  consumed: z.number().default(0),
  returned: z.number().default(0),
  fencingEpoch: z.number().optional(),
  state: z.string().optional(),
})
export type AccountView = z.infer<typeof accountViewSchema>

export const recomputeSchema = z.object({
  conversions: z.number().default(0),
  credits: z.number().default(0),
  completedAt: z.string().optional(),
})

export function parseWith<T>(schema: z.ZodType<T>, value: unknown): T {
  return schema.parse(value)
}
