import type { DashboardMetrics } from '../api/schemas'

export const MEASUREMENT_EVENT_LABELS = { revenue: '事件收入', cost: '事件补贴成本', roi: '事件口径 ROI' } as const

/** 本期净补贴成本不为正时，无法用它衡量投入回报。旧接口可能仍返回数值 0。 */
export function displayMeasurementRoi(totals: DashboardMetrics, unknown = '—') {
  if (totals.fundingCostMinor <= 0) return '不适用'
  return String(totals.roi ?? unknown)
}
