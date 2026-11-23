import { z } from 'zod'
import { releaseViewSchema } from './schemas'

export const OperationState = { PENDING: 'PENDING', APPLIED: 'APPLIED', EXPIRED: 'EXPIRED', REJECTED: 'REJECTED' } as const
export const ScheduleState = { SCHEDULED: 'SCHEDULED', PAUSED: 'PAUSED', RUNNING: 'RUNNING', APPLIED: 'APPLIED', REJECTED: 'REJECTED', EXPIRED: 'EXPIRED' } as const
export const BundleMemberLimit = { MIN: 2, MAX: 8 } as const
const id = z.string().min(1)
const count = z.number().int().nonnegative().safe()
const version = z.number().int().positive().safe()
const instant = z.iso.datetime({ offset: true })
const memberSchema = releaseViewSchema.shape.manifest.extend({ environment: id, cell: id, runtime: id, namespace: id, expiresAt: instant })
const bundleSchema = z.object({
  bundleId: id, campaignId: id, version, state: z.literal('PREPARING'), environment: id, cell: id,
  contentHash: id, members: z.array(memberSchema).min(BundleMemberLimit.MIN).max(BundleMemberLimit.MAX), createdBy: id, createdAt: instant,
})
const readinessSchema = z.object({
  bundleId: id, version, contentHash: id, runtimeReady: z.boolean(), checkedAt: instant, expiresAt: instant,
  members: z.array(z.object({ manifestId: id, runtime: id, namespace: id, readyReplicas: count, readyCapacity: count,
    ignoredEvidence: count, runtimeReady: z.boolean() })).min(BundleMemberLimit.MIN).max(BundleMemberLimit.MAX),
})
const action = z.enum(['ACTIVATE', 'ROLLBACK'])
const operationSchema = z.object({
  operationId: id, campaignId: id, version, action, activationSequence: version,
  state: z.enum(OperationState), reasonCode: id.nullish(),
  createdAt: instant, updatedAt: instant,
})
const summarySchema = z.object({ appliedSequence: count, appliedVersion: version.nullable(), latestOperation: operationSchema.nullable(), rollbackEligible: z.boolean() })
const scheduleSchema = z.object({
  scheduleId: id, campaignId: id, version, expectedAppliedSequence: count, revision: version, dueAt: instant, applyBefore: instant,
  state: z.enum(ScheduleState),
  operationId: id.nullish(), reasonCode: id.nullish(), createdAt: instant, updatedAt: instant,
})
export type ReleaseBundle = z.infer<typeof bundleSchema>
export type BundleReadiness = z.infer<typeof readinessSchema>
export type BundleOperation = z.infer<typeof operationSchema>
export type BundleSchedule = z.infer<typeof scheduleSchema>
export type BundleAction = z.infer<typeof action>
export type BundleOperationInput = { version: number; expectedAppliedSequence: number; action: BundleAction }
export type BundleScheduleInput = { version: number; expectedAppliedSequence: number; dueAt: string }
type Request = <T>(path: string, init: RequestInit | undefined, parse: (value: unknown) => T) => Promise<T>
const base = (campaignId: string) => `/api/v1/campaigns/${encodeURIComponent(campaignId)}/release-bundles`
const mismatch = () => new Error('回执与所选活动或版本不一致，请重新查询。')

function checked<T>(schema: z.ZodType<T>, value: unknown): T {
  const parsed = schema.safeParse(value)
  if (!parsed.success) throw new Error('组合发布服务返回的数据不完整，请重新查询。')
  return parsed.data
}

function scoped<T extends { campaignId: string; version: number }>(value: T, campaignId: string, expectedVersion?: number) {
  if (value.campaignId !== campaignId || expectedVersion !== undefined && value.version !== expectedVersion) throw mismatch()
  return value
}

/** 组合命令共享现有会话与错误处理，读回时校验业务身份，避免误用旧活动的回执。 */
export function releaseBundleClient(request: Request, headers: (key: string) => Record<string, string>) {
  const post = (key: string, body: unknown): RequestInit => ({ method: 'POST', headers: headers(key), body: JSON.stringify(body) })
  return {
    latestBundle: (campaignId: string) => request(`${base(campaignId)}/latest`, undefined, data => scoped(checked(bundleSchema, data), campaignId)),
    bundleVersion: (campaignId: string, target: number) => request(`${base(campaignId)}/versions/${target}`, undefined, data => scoped(checked(bundleSchema, data), campaignId, target)),
    prepareBundle: (campaignId: string, expectedVersion: number, manifestIds: string[], key: string) =>
      request(base(campaignId), post(key, { expectedVersion, manifestIds }), data => {
        const result = scoped(checked(bundleSchema, data), campaignId, expectedVersion + 1)
        const members = new Set(result.members.map(item => item.manifestId))
        if (members.size !== manifestIds.length || manifestIds.some(item => !members.has(item))) throw mismatch()
        return result
      }),
    bundleReadiness: (bundle: ReleaseBundle, prepare = false) =>
      request(`${base(bundle.campaignId)}/versions/${bundle.version}${prepare ? ':prepare-runtime' : '/readiness'}`, prepare ? { method: 'POST' } : undefined, data => {
        const result = checked(readinessSchema, data)
        const members = new Map(bundle.members.map(item => [item.manifestId, item]))
        if (result.bundleId !== bundle.bundleId || result.version !== bundle.version || result.contentHash !== bundle.contentHash
          || result.runtimeReady !== result.members.every(item => item.runtimeReady)
          || result.members.length !== members.size || new Set(result.members.map(item => item.manifestId)).size !== members.size
          || result.members.some(item => members.get(item.manifestId)?.runtime !== item.runtime || members.get(item.manifestId)?.namespace !== item.namespace)) throw mismatch()
        return result
      }),
    bundleOperations: (bundle: ReleaseBundle) => {
      const params = new URLSearchParams({ environment: bundle.environment, cell: bundle.cell, version: String(bundle.version) })
      return request(`${base(bundle.campaignId)}/operations?${params}`, undefined, data => {
        const summary = checked(summarySchema, data)
        if (summary.latestOperation) scoped(summary.latestOperation, bundle.campaignId)
        if ((summary.appliedSequence === 0) !== (summary.appliedVersion === null)) throw mismatch()
        return summary
      })
    },
    startBundleOperation: (campaignId: string, input: BundleOperationInput, key: string) =>
      request(`${base(campaignId)}/operations`, post(key, input), data => {
        const operation = scoped(checked(operationSchema, data), campaignId, input.version)
        if (operation.action !== input.action) throw mismatch()
        return operation
      }),
    bundleOperation: (campaignId: string, operationId: string, retry = false) =>
      request(`${base(campaignId)}/operations/${encodeURIComponent(operationId)}${retry ? ':retry' : ''}`, retry ? { method: 'POST' } : undefined, data => {
        const operation = scoped(checked(operationSchema, data), campaignId)
        if (operation.operationId !== operationId) throw mismatch()
        return operation
      }),
    bundleSchedules: (campaignId: string, target: number) =>
      request(`${base(campaignId)}/schedules?version=${target}`, undefined, data => checked(z.array(scheduleSchema).max(20), data).map(item => scoped(item, campaignId, target))),
    scheduleBundle: (campaignId: string, input: BundleScheduleInput, key: string) =>
      request(`${base(campaignId)}/schedules`, post(key, input), data => {
        const schedule = scoped(checked(scheduleSchema, data), campaignId, input.version)
        if (schedule.expectedAppliedSequence !== input.expectedAppliedSequence || Date.parse(schedule.dueAt) !== Date.parse(input.dueAt)) throw mismatch()
        return schedule
      }),
    changeBundleSchedule: (campaignId: string, scheduleId: string, change: 'pause' | 'resume', expectedRevision: number) =>
      request(`${base(campaignId)}/schedules/${encodeURIComponent(scheduleId)}:${change}?expectedRevision=${expectedRevision}`, { method: 'POST' }, data => {
        const schedule = scoped(checked(scheduleSchema, data), campaignId)
        if (schedule.scheduleId !== scheduleId) throw mismatch()
        return schedule
      }),
  }
}
