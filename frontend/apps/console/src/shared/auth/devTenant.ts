export const DEV_TENANT_KEY = 'marketing.dev.tenantId'
export const DEFAULT_DEV_TENANT = 'retail-cn'

export function getDevTenantId(): string {
  if (typeof window === 'undefined') return DEFAULT_DEV_TENANT
  return window.sessionStorage.getItem(DEV_TENANT_KEY)?.trim() || DEFAULT_DEV_TENANT
}

export function setDevTenantId(tenantId: string) {
  window.sessionStorage.setItem(DEV_TENANT_KEY, tenantId.trim() || DEFAULT_DEV_TENANT)
}
