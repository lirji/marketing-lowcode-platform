export function problemDetail(error: unknown, fallback = '服务当前不可用，请保留筛选条件后重试。') {
  if (error && typeof error === 'object' && 'message' in error && typeof error.message === 'string' && error.message.trim()) {
    return error.message
  }
  return fallback
}
