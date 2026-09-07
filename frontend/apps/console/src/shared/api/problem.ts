export function problemDetail(error: unknown, fallback = '服务当前不可用，请保留筛选条件后重试。') {
  if (error && typeof error === 'object' && 'message' in error && typeof error.message === 'string' && error.message.trim()) {
    return error.message
  }
  return fallback
}

export function problemCode(error: unknown): string | undefined {
  if (error && typeof error === 'object' && 'code' in error && typeof (error as { code?: unknown }).code === 'string') {
    const code = (error as { code: string }).code.trim()
    return code || undefined
  }
  return undefined
}

const CATALOG_HINTS: Record<string, string> = {
  BENEFIT_CATALOG_AUTH_REQUIRED: '营销还不能调权益目录（机器账号未配），不是货主写错。',
  BENEFIT_CATALOG_TENANT_MISMATCH: '当前货主被权益拒绝，请与建 SKU 时的货主对齐。',
  BENEFIT_CATALOG_ACCESS_DENIED: '当前身份不能读该货主目录。',
}

export function catalogProblemDetail(error: unknown, tenantId?: string) {
  const code = problemCode(error)
  if (code === 'BENEFIT_CATALOG_TENANT_MISMATCH') {
    return `${code}：当前货主 ${tenantId?.trim() || '未声明'} 被权益拒绝，请与建 SKU 时的货主对齐。`
  }
  if (code && CATALOG_HINTS[code]) return `${code}：${CATALOG_HINTS[code]}`
  return problemDetail(error)
}
