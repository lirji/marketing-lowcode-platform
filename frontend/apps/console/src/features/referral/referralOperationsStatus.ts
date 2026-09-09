export function formatNullableCount(value: number | null | undefined): string {
  if (value === null || value === undefined) return '未计算'
  return String(value)
}

export function qualificationLabel(state: string | null | undefined, counted: boolean | null | undefined): string {
  if (!state) return '资格尚未投影'
  if (counted === true) return `${state} · 计入人数`
  if (counted === false) return `${state} · 未计入人数`
  return state
}

export function deliveryLabel(state: string): string {
  if (state === 'SUCCEEDED') return '渠道成功回执'
  if (state === 'ACCEPTED') return '本地受理，非到账'
  if (state === 'PENDING') return '渠道处理中，非到账'
  if (state === 'UNKNOWN') return '结果未知，非到账'
  if (state === 'FAILED_FINAL') return '渠道最终失败'
  if (state === 'NOT_SUBMITTED') return '尚未提交渠道'
  return state
}

export function watermarkText(asOf?: string, consistency?: string): string {
  if (!asOf) return '数据水位：未知'
  return `数据水位：${asOf}${consistency ? ` · ${consistency}` : ''}（查询结束时刻，不是跨页快照）`
}
