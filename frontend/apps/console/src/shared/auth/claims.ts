export function splitClaim(value: unknown): string[] {
  if (Array.isArray(value)) {
    return value.flatMap((item) => {
      if (item && typeof item === 'object' && 'name' in item) {
        return splitClaim(String((item as { name: unknown }).name))
      }
      return splitClaim(item)
    })
  }
  if (typeof value === 'string') return value.split(',').map((item) => item.trim()).filter(Boolean)
  return []
}

export function toMarketingPermission(name: string): string {
  if (name === 'marketing.admin' || name === '*') return name
  if (name.includes(':')) return name
  const separator = name.lastIndexOf('.')
  return separator > 0 ? `${name.slice(0, separator)}:${name.slice(separator + 1)}` : name
}

export function permissionNames(value: unknown): string[] {
  const names = splitClaim(value).map(toMarketingPermission)
  return names.includes('marketing.admin') || names.includes('*') ? ['*'] : names
}

export function decodeJwtPayload(token: string): Record<string, unknown> {
  const parts = token.split('.')
  if (parts.length < 2) return {}
  try {
    const normalized = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const padded = normalized + '='.repeat((4 - (normalized.length % 4)) % 4)
    return JSON.parse(atob(padded)) as Record<string, unknown>
  } catch {
    return {}
  }
}

function firstText(...values: unknown[]): string {
  for (const value of values) {
    if (typeof value === 'string' && value.trim()) return value.trim()
  }
  return ''
}

/**
 * 货主业务租户。与营销后端 TenantContextFilter 一致：只认 tenant_id /
 * properties.tenant_id，绝不回退 Casdoor owner（登录组织）。
 */
export function businessTenantFromPayload(payload: Record<string, unknown>): string {
  const nested = payload.properties && typeof payload.properties === 'object' && !Array.isArray(payload.properties)
    ? (payload.properties as Record<string, unknown>).tenant_id
    : undefined
  return firstText(payload.tenant_id, nested)
}
