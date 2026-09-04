export type ProblemDetail = {
  type: string
  title: string
  status: number
  detail: string
  instance?: string
  code?: string
  retryable?: boolean
}

export type TenantScope = {
  tenantId: string
  organizationId: string
  shopId: string
}
