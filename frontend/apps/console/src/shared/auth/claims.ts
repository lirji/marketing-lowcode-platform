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
