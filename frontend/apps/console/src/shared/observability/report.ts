import { runtimeConfig } from '../config/runtime'

export type ClientErrorReport = {
  code: string
  message: string
  tenantId?: string
  actorId?: string
  path?: string
}

function sanitize(value: string, max = 240) {
  return value.replace(/\s+/g, ' ').slice(0, max)
}

export function reportClientError(report: ClientErrorReport) {
  const payload = {
    code: sanitize(report.code, 64),
    message: sanitize(report.message),
    tenantId: report.tenantId ? sanitize(report.tenantId, 64) : undefined,
    actorId: report.actorId ? sanitize(report.actorId, 64) : undefined,
    path: report.path ? sanitize(report.path, 160) : window.location.pathname,
  }
  console.error('[console]', payload.code, payload.message)
  const body = JSON.stringify(payload)
  const url = `${runtimeConfig.apiBaseUrl}/client-error`
  try {
    if (navigator.sendBeacon) {
      navigator.sendBeacon(url, new Blob([body], { type: 'application/json' }))
      return
    }
  } catch {
    /* fall through */
  }
  void fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body, keepalive: true }).catch(() => undefined)
}

export function reportUnknownError(cause: unknown, context: Omit<ClientErrorReport, 'code' | 'message'> & { code?: string }) {
  const message = cause instanceof Error ? cause.message : 'unknown error'
  reportClientError({ code: context.code ?? 'UI_ERROR', message, tenantId: context.tenantId, actorId: context.actorId, path: context.path })
}
