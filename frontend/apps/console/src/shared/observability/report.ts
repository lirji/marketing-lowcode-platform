import type { ClientErrorEvent } from '@marketing/contracts'
import { runtimeConfig } from '../config/runtime'

const CLIENT_ERROR_PATH = '/api/v1/client-errors'
const REPORT_WINDOW_MS = 60_000
const MAX_REPORTS_PER_WINDOW = 10
const REPORT_TIMEOUT_MS = 3_000
let contextProvider: () => Record<string, string> | undefined = () => undefined
let windowStarted = 0
let sentInWindow = 0

type ClientErrorReport = { source: ClientErrorEvent['source']; path?: string; httpStatus?: number; backendTraceId?: string }

/** 由身份模块提供当前会话头；不反向调用 API client，以免采集故障递归上报。 */
export function setClientErrorContextProvider(provider: () => Record<string, string> | undefined) {
  contextProvider = provider
}

function routeClass(path: string): ClientErrorEvent['route'] {
  const clean = path.split(/[?#]/, 1)[0]
  if (clean === '/') return 'HOME'
  const segment = clean.startsWith('/api/v1/') ? clean.slice(8).split('/')[0] : clean.split('/')[1]
  const routes: Record<string, ClientErrorEvent['route']> = {
    campaigns: 'CAMPAIGNS', designers: 'DESIGNERS', definitions: 'DESIGNERS', dmn: 'DESIGNERS',
    assets: 'ASSETS', templates: 'ASSETS', audiences: 'ASSETS', benefits: 'ASSETS',
    releases: 'RELEASES', approvals: 'GOVERNANCE', governance: 'GOVERNANCE',
    analytics: 'ANALYTICS', measurements: 'ANALYTICS', referrals: 'REFERRALS', 'referral-campaigns': 'REFERRALS', 'referral-operations': 'REFERRALS',
    login: 'AUTH', auth: 'AUTH', operations: 'OPERATIONS',
  }
  return Object.hasOwn(routes, segment) ? routes[segment] : clean.startsWith('/api/') ? 'API' : 'OTHER'
}

/** 尽力诊断且不阻塞业务：只构造允许字段，日志与网络都不接收原始异常文本。 */
export function reportClientError(report: ClientErrorReport) {
  try {
    if (runtimeConfig.demoMode) return
    const identityHeaders = contextProvider()
    if (!identityHeaders) return
    const now = Date.now()
    if (now - windowStarted >= REPORT_WINDOW_MS || now < windowStarted) { windowStarted = now; sentInWindow = 0 }
    if (sentInWindow >= MAX_REPORTS_PER_WINDOW) return
    sentInWindow++
    const status = report.httpStatus ?? 0
    const payload: ClientErrorEvent = {
      schemaVersion: 1, source: report.source, route: routeClass(report.path ?? window.location.pathname),
      httpStatus: Number.isInteger(status) && status >= 100 && status <= 599 ? status : 0,
      ...(/^[0-9a-fA-F]{32}$/.test(report.backendTraceId ?? '') ? { backendTraceId: report.backendTraceId!.toLowerCase() } : {}),
    }
    console.error('[console]', payload.source, payload.route, payload.httpStatus)
    const controller = new AbortController()
    const timeout = window.setTimeout(() => controller.abort(), REPORT_TIMEOUT_MS)
    // 不排队、不重试；身份只用于认证头，服务端只从验证后的上下文取得租户。
    let pending: Promise<Response>
    try { pending = fetch(`${runtimeConfig.apiBaseUrl}${CLIENT_ERROR_PATH}`, {
      method: 'POST', headers: { ...identityHeaders, 'Content-Type': 'application/json' },
      body: JSON.stringify(payload), keepalive: true, signal: controller.signal,
    }) } catch { window.clearTimeout(timeout); return }
    void pending.catch(() => undefined).finally(() => window.clearTimeout(timeout))
  } catch { /* 诊断能力不可用不能覆盖原业务错误，也不能触发下一轮诊断。 */ }
}

export function reportUnknownError(cause: unknown, context: { code?: string; path?: string }) {
  // 连 message/getter 都不读取，防止异常对象夹带用户输入或触发二次异常。
  void cause
  const source = context.code === 'WINDOW_ERROR' ? 'WINDOW' : context.code === 'UNHANDLED_REJECTION' ? 'PROMISE'
    : context.code === 'REACT_BOUNDARY' ? 'REACT' : 'UNKNOWN'
  reportClientError({ source, path: context.path })
}
