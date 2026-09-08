import type { ProblemDetail } from '@marketing/contracts'
import { campaignCreateBody } from '../auth/scopeChoices'
import { runtimeConfig } from '../config/runtime'
import { getDevTenantId } from '../auth/devTenant'
import { reportClientError } from '../observability/report'
import {
  accountViewSchema,
  artifactViewSchema,
  approvalViewSchema,
  audiencePreviewSchema,
  audienceSnapshotSchema,
  audienceViewSchema,
  awardIntentViewSchema,
  benefitSkuSchema,
  benefitViewSchema,
  campaignSchema,
  contactViewSchema,
  compileReportSchema,
  dashboardSchema,
  definitionBundleSchema,
  enrollmentViewSchema,
  fieldDefinitionSchema,
  graphDefinitionSchema,
  killSwitchViewSchema,
  nodeRegistrySchema,
  parseWith,
  problemDetailSchema,
  quarantineViewSchema,
  reconciliationSchema,
  recomputeSchema,
  releaseViewSchema,
  seriesSchema,
  simulationSchema,
  templateViewSchema,
  traceViewSchema,
  validationResultSchema,
  type ApprovalRole,
  type CompileRequest,
  type Campaign,
  type GraphDefinition,
  type StageReleaseRequest,
} from './schemas'

let accessTokenProvider: () => string | undefined = () => undefined
let unauthorizedHandler: () => void = () => undefined
let identityProvider: () => { tenantId?: string; actorId?: string } = () => ({})

export function setAccessTokenProvider(provider: () => string | undefined) {
  accessTokenProvider = provider
}

export function setUnauthorizedHandler(handler: () => void) {
  unauthorizedHandler = handler
}

export function setIdentityProvider(provider: () => { tenantId?: string; actorId?: string }) {
  identityProvider = provider
}

export class ApiProblem extends Error {
  readonly status: number
  readonly code?: string
  readonly retryable?: boolean

  constructor(readonly problem: ProblemDetail) {
    super(problem.detail || problem.title)
    this.name = 'ApiProblem'
    this.status = problem.status
    this.code = problem.code
    this.retryable = problem.retryable
  }
}

function identityHeaders(): Record<string, string> {
  if (runtimeConfig.authMode === 'OIDC') {
    const token = accessTokenProvider()
    return token ? { Authorization: `Bearer ${token}` } : {}
  }
  return {
    'X-Dev-Tenant-Id': getDevTenantId(),
    'X-Dev-Organization-Ids': getDevTenantId() === 'retail-cn' ? 'retail-business' : getDevTenantId(),
    'X-Dev-Shop-Ids': 'all-shops',
    'X-Dev-Actor-Id': 'console-admin',
    'X-Dev-Permissions': '*',
  }
}

function mergeHeaders(init?: RequestInit): Headers {
  const headers = new Headers(init?.headers)
  const identity = identityHeaders()
  for (const [key, value] of Object.entries(identity)) {
    if (!headers.has(key)) headers.set(key, value)
  }
  const method = (init?.method ?? 'GET').toUpperCase()
  if (init?.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  if (method !== 'GET' && method !== 'HEAD' && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (!headers.has('X-Request-Id')) headers.set('X-Request-Id', crypto.randomUUID())
  return headers
}

async function request<T>(path: string, init: RequestInit | undefined, parse: (value: unknown) => T): Promise<T> {
  const method = (init?.method ?? 'GET').toUpperCase()
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), runtimeConfig.requestTimeoutMs)
  const parentAbort = init?.signal
  const onAbort = () => controller.abort()
  parentAbort?.addEventListener('abort', onAbort)
  try {
    const response = await fetch(`${runtimeConfig.apiBaseUrl}${path}`, {
      ...init,
      method,
      headers: mergeHeaders(init),
      signal: controller.signal,
    })
    if (response.status === 401) {
      unauthorizedHandler()
    }
    if (!response.ok) {
      const raw = await response.json().catch(() => ({ title: response.statusText, status: response.status, detail: '请求失败', type: 'about:blank' }))
      const problem = problemDetailSchema.catch({
        type: 'about:blank',
        title: response.statusText || '请求失败',
        status: response.status,
        detail: '请求失败',
      }).parse(raw)
      if (response.status >= 500) {
        const identity = identityProvider()
        reportClientError({ code: problem.code ?? `HTTP_${response.status}`, message: problem.detail, tenantId: identity.tenantId, actorId: identity.actorId, path })
      }
      throw new ApiProblem(problem)
    }
    if (response.status === 204) return undefined as T
    return parse(await response.json())
  } catch (cause) {
    if (cause instanceof ApiProblem) throw cause
    if (cause instanceof DOMException && cause.name === 'AbortError') {
      throw new ApiProblem({ type: 'about:blank', title: 'Gateway Timeout', status: 504, detail: '请求超时或已取消', retryable: true })
    }
    throw new ApiProblem({ type: 'about:blank', title: 'Network Error', status: 0, detail: cause instanceof Error ? cause.message : '网络不可用', retryable: true })
  } finally {
    window.clearTimeout(timeout)
    parentAbort?.removeEventListener('abort', onAbort)
  }
}

function commandHeaders(idempotencyKey?: string): Record<string, string> {
  return { 'Idempotency-Key': idempotencyKey ?? crypto.randomUUID() }
}

export const api = {
  demoMode: runtimeConfig.demoMode,
  campaigns: () => request('/api/v1/campaigns', undefined, (value) => zArray(campaignSchema, value)),
  createCampaign: (payload: { name: string; objective: string; organizationId: string; shopId: string }) =>
    request('/api/v1/campaigns', {
      method: 'POST',
      headers: commandHeaders(),
      body: JSON.stringify(campaignCreateBody(payload)),
    }, (value) => parseWith(campaignSchema, value)),
  saveDefinition: (payload: { campaignId: string; graph: GraphDefinition }) =>
    request('/api/v1/definitions', { method: 'POST', headers: commandHeaders(), body: JSON.stringify(payload) }, (value) => parseWith(definitionBundleSchema, value)),
  latestDefinition: (campaignId: string, dialect: string) =>
    request(`/api/v1/definitions/latest?campaignId=${encodeURIComponent(campaignId)}&dialect=${encodeURIComponent(dialect)}`, undefined, (value) => parseWith(definitionBundleSchema, value)),
  getDefinition: (definitionId: string, version: number) =>
    request(`/api/v1/definitions/${encodeURIComponent(definitionId)}/versions/${version}`, undefined, (value) => parseWith(definitionBundleSchema, value)),
  nodeRegistry: () => request('/api/v1/registries/nodes', undefined, (value) => zArray(nodeRegistrySchema, value)),
  validate: (definitionId: string, version: number) =>
    request(`/api/v1/definitions/${encodeURIComponent(definitionId)}/versions/${version}:validate`, { method: 'POST', headers: commandHeaders() }, (value) => parseWith(validationResultSchema, value)),
  simulate: (definitionId: string, version: number, facts: Record<string, string>) =>
    request(`/api/v1/definitions/${encodeURIComponent(definitionId)}/versions/${version}:simulate`, { method: 'POST', headers: commandHeaders(), body: JSON.stringify(facts) }, (value) => parseWith(simulationSchema, value)),
  submit: (definitionId: string, version: number) =>
    request(`/api/v1/definitions/${encodeURIComponent(definitionId)}/versions/${version}:submit`, { method: 'POST', headers: commandHeaders() }, (value) => parseWith(approvalViewSchema, value)),
  approvals: () => request('/api/v1/approvals', undefined, (value) => zArray(approvalViewSchema, value)),
  decideApproval: (caseId: string, role: ApprovalRole, decision: 'APPROVE' | 'REJECT' = 'APPROVE', comment?: string) =>
    request(`/api/v1/approvals/${encodeURIComponent(caseId)}/decisions`, { method: 'POST', headers: commandHeaders(), body: JSON.stringify({ role, decision, comment }) }, (value) => parseWith(approvalViewSchema, value)),
  releases: () => request('/api/v1/releases', undefined, (value) => zArray(releaseViewSchema, value)),
  compile: (payload: CompileRequest, idempotencyKey?: string) =>
    request('/api/v1/compile', { method: 'POST', headers: commandHeaders(idempotencyKey), body: JSON.stringify(payload) }, (value) => parseWith(compileReportSchema, value)),
  getArtifact: (artifactId: string) =>
    request(`/api/v1/artifacts/${encodeURIComponent(artifactId)}`, undefined, (value) => parseWith(artifactViewSchema, value)),
  stageRelease: (payload: StageReleaseRequest, idempotencyKey: string) =>
    request('/api/v1/releases', { method: 'POST', headers: commandHeaders(idempotencyKey), body: JSON.stringify(payload) }, (value) => parseWith(releaseViewSchema, value)),
  activateRelease: (manifestId: string, idempotencyKey?: string) =>
    request(`/api/v1/releases/${encodeURIComponent(manifestId)}:activate`, { method: 'POST', headers: commandHeaders(idempotencyKey) }, (value) => parseWith(releaseViewSchema, value)),
  rollbackRelease: (manifestId: string, targetGeneration: number) =>
    request(`/api/v1/releases/${encodeURIComponent(manifestId)}:rollback`, { method: 'POST', headers: commandHeaders(), body: JSON.stringify({ targetGeneration }) }, (value) => parseWith(releaseViewSchema, value)),
  setKillSwitch: (namespace: string, enabled: boolean, reason: string) =>
    request(`/api/v1/releases/kill-switches/${encodeURIComponent(namespace)}`, { method: 'PUT', headers: commandHeaders(), body: JSON.stringify({ enabled, reason }) }, (value) => parseWith(killSwitchViewSchema, value)),
  dashboard: (from: string, to: string) =>
    request(`/api/v1/measurements/dashboard?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`, undefined, (value) => parseWith(dashboardSchema, value)),
  series: (from: string, to: string, granularity: 'hour' | 'day') =>
    request(`/api/v1/measurements/series?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&granularity=${granularity}`, undefined, (value) => parseWith(seriesSchema, value)),
  recomputeAttribution: () =>
    request('/api/v1/measurements/attribution:recompute', { method: 'POST', headers: commandHeaders(), body: JSON.stringify({}) }, (value) => parseWith(recomputeSchema, value)),
  audiences: () => request('/api/v1/audiences', undefined, (value) => zArray(audienceViewSchema, value)),
  createAudience: (payload: { segmentId: string; name: string; rule: { match: string; conditions: { fieldId: string; operator: string; value: string }[] } }) =>
    request('/api/v1/audiences', { method: 'POST', headers: commandHeaders(), body: JSON.stringify(payload) }, (value) => parseWith(audienceViewSchema, value)),
  previewAudience: (segmentId: string, version: number, profiles: { subjectToken: string; attributes: Record<string, string> }[]) =>
    request(`/api/v1/audiences/${encodeURIComponent(segmentId)}/versions/${version}:preview`, { method: 'POST', headers: commandHeaders(), body: JSON.stringify(profiles) }, (value) => parseWith(audiencePreviewSchema, value)),
  snapshotAudience: (segmentId: string, version: number, payload: { subjectTokens: string[]; asOf: string; watermark: string; expiresAt: string }) =>
    request(`/api/v1/audiences/${encodeURIComponent(segmentId)}/versions/${version}/snapshots`, { method: 'POST', headers: commandHeaders(), body: JSON.stringify(payload) }, (value) => parseWith(audienceSnapshotSchema, value)),
  fields: () => request('/api/v1/fields', undefined, (value) => zArray(fieldDefinitionSchema, value)),
  templates: () => request('/api/v1/templates', undefined, (value) => zArray(templateViewSchema, value)),
  benefits: () => request('/api/v1/benefits', undefined, (value) => zArray(benefitViewSchema, value)),
  benefit: (benefitId: string) =>
    request(`/api/v1/benefits/${encodeURIComponent(benefitId)}`, undefined, (value) => parseWith(benefitViewSchema, value)),
  benefitSkus: (status = 'ACTIVE') =>
    request(`/api/v1/benefit-skus?status=${encodeURIComponent(status)}`, undefined, (value) => zArray(benefitSkuSchema, value)),
  putBenefit: (benefitId: string, payload: { name: string; status: string; resourceKey: string; benefitSkuId: string | null; policy: Record<string, unknown> }) =>
    request(`/api/v1/benefits/${encodeURIComponent(benefitId)}`, { method: 'PUT', headers: commandHeaders(), body: JSON.stringify(payload) }, (value) => parseWith(benefitViewSchema, value)),
  fundingAccounts: () => request('/api/v1/funding/accounts', undefined, (value) => zArray(accountViewSchema, value)),
  awardIntents: (campaignId: string, query?: { limit?: number; cursor?: string }) => {
    const params = new URLSearchParams()
    params.set('campaignId', campaignId)
    params.set('limit', String(query?.limit ?? 20))
    if (query?.cursor) params.set('cursor', query.cursor)
    return request(`/api/v1/award-intents?${params}`, undefined, (value) => zArray(awardIntentViewSchema, value))
  },
  traceByRequest: (requestId: string) =>
    request(`/api/v1/traces/requests/${encodeURIComponent(requestId)}`, undefined, (value) => parseWith(traceViewSchema, value)),
  traceByOrder: (orderId: string) =>
    request(`/api/v1/traces/orders/${encodeURIComponent(orderId)}`, undefined, (value) => parseWith(traceViewSchema, value)),
  enrollment: (enrollmentId: string) =>
    request(`/api/v1/enrollments/${encodeURIComponent(enrollmentId)}`, undefined, (value) => parseWith(enrollmentViewSchema, value)),
  enrollments: (query?: { status?: string; journeyId?: string; limit?: number }) => {
    const params = new URLSearchParams()
    if (query?.status) params.set('status', query.status)
    if (query?.journeyId) params.set('journeyId', query.journeyId)
    params.set('limit', String(query?.limit ?? 50))
    return request(`/api/v1/enrollments?${params}`, undefined, (value) => zArray(enrollmentViewSchema, value))
  },
  contact: (contactKey: string) =>
    request(`/api/v1/contacts/${encodeURIComponent(contactKey)}`, undefined, (value) => parseWith(contactViewSchema, value)),
  contacts: (query?: { state?: string; limit?: number }) => {
    const params = new URLSearchParams()
    if (query?.state) params.set('state', query.state)
    params.set('limit', String(query?.limit ?? 50))
    return request(`/api/v1/contacts?${params}`, undefined, (value) => zArray(contactViewSchema, value))
  },
  quarantine: () => request('/api/v1/quarantine', undefined, (value) => zArray(quarantineViewSchema, value)),
  replayQuarantine: (quarantineId: string) =>
    request(`/api/v1/quarantine/${encodeURIComponent(quarantineId)}:replay`, { method: 'POST', headers: commandHeaders() }, (value) => value),
  reconciliation: () => request('/api/v1/funding/reconciliation', undefined, (value) => parseWith(reconciliationSchema, value)),
}

function zArray<T>(schema: { parse: (value: unknown) => T }, value: unknown): T[] {
  if (!Array.isArray(value)) return []
  return value.map((item) => schema.parse(item))
}

export async function optionalResource<T>(load: () => Promise<T>): Promise<T | undefined> {
  try {
    return await load()
  } catch (cause) {
    if (cause instanceof ApiProblem && (cause.status === 404 || cause.status === 405)) return undefined
    throw cause
  }
}

export type { Campaign, GraphDefinition }
export { graphDefinitionSchema }
