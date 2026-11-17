export type ProblemDetail = {
  type: string
  title: string
  status: number
  detail: string
  instance?: string
  code?: string
  retryable?: boolean
  traceId?: string
}

export type TenantScope = {
  tenantId: string
  organizationId: string
  shopId: string
}

/** 错误采集只承载固定分类；不允许任意消息、路径、身份或业务数据。 */
export type ClientErrorEvent = {
  schemaVersion: 1
  source: 'API' | 'WINDOW' | 'PROMISE' | 'REACT' | 'UNKNOWN'
  route: 'HOME' | 'CAMPAIGNS' | 'DESIGNERS' | 'ASSETS' | 'RELEASES' | 'GOVERNANCE' | 'ANALYTICS' | 'REFERRALS' | 'AUTH' | 'OPERATIONS' | 'API' | 'OTHER'
  httpStatus: number
  backendTraceId?: string
}
