import { businessDayStart, displayTimestamp } from '../../components/dateTime'
import { downloadCsv, formatCsv } from '../../shared/downloadCsv'
import type { DashboardMetrics, SeriesPoint } from '../../shared/api/schemas'
import { measurementCoverage } from '../../shared/model/measurementCoverage'
import { displayMeasurementRoi, MEASUREMENT_EVENT_LABELS } from '../../shared/model/measurementMetrics'

export const ANALYTICS_PERIODS = { TODAY: '今天', WEEK: '7 天', MONTH: '30 天' } as const
export type AnalyticsPeriod = keyof typeof ANALYTICS_PERIODS
export type AnalyticsRange = { from: string; to: string; granularity: 'hour' | 'day' }
export type AnalyticsReport = { tenantId: string; campaignId?: string; range: AnalyticsRange; dashboard: DashboardMetrics; points: SeriesPoint[]; fetchedAt: string; dayOffset?: 'Z' | '+08:00' | null }
const REPORT_VALUE_ERROR = '报告包含超出精确范围的金额或计数，无法可靠显示或导出'
const DAY_MS = 24 * 60 * 60 * 1000
export const MAX_ANALYTICS_RANGE_MS = 366 * DAY_MS

export function seriesBucketDescription(granularity: AnalyticsRange['granularity'], dayOffset?: AnalyticsReport['dayOffset']) {
  if (granularity === 'hour') return '按小时分桶'
  return dayOffset === '+08:00' ? '按北京时间自然日分桶' : '按 UTC 自然日分桶'
}

/** 两个统计接口共享同一组时间值，渲染和下载不会隐式改变查询范围。 */
export function analyticsRange(period: AnalyticsPeriod, now = new Date()): AnalyticsRange {
  const from = period === 'TODAY' ? businessDayStart(now) : new Date(now.getTime() - (period === 'WEEK' ? 7 : 30) * DAY_MS)
  return { from: from.toISOString(), to: now.toISOString(), granularity: period === 'TODAY' ? 'hour' : 'day' }
}

/** 金额仍以接口分值为准；非法或超出JS精确整数范围时不展示近似金额。 */
export function displayMinor(value: number | bigint) {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) return '金额超出精确显示范围'
  const minor = BigInt(value), absolute = minor < 0n ? -minor : minor
  return `${minor < 0n ? '-' : ''}${absolute / 100n}.${String(absolute % 100n).padStart(2, '0')}`
}

/** 拒绝JS已经无法精确表示的Long；不能把近似金额写入可下载报告。 */
export function validateReport(report: AnalyticsReport) {
  const { dashboard, points } = report
  const values = [dashboard.attributedRevenueMinor, dashboard.fundingCostMinor, ...Object.values(dashboard.counts),
    ...points.flatMap(point => [point.revenueMinor, point.costMinor, point.conversions])]
  if (!values.every(Number.isSafeInteger)) throw new Error(REPORT_VALUE_ERROR)
  return report
}

export function reportCsv(report: AnalyticsReport) {
  validateReport(report)
  const { range, dashboard, points, fetchedAt } = report
  if (measurementCoverage(range.from, range.to, dashboard.completeThrough) !== 'complete') {
    throw new Error('所选范围的数据尚未齐全，请查询已覆盖范围后导出')
  }
  const rows: (string | number)[][] = [
    ['分类', '字段或时间', '值', '单位', '说明'],
    ['查询范围', '租户', report.tenantId, '', '当前认证租户'],
    ['查询范围', '活动', report.campaignId || '全部活动', '', '按事件记录的活动ID筛选'],
    ['查询范围', '开始', displayTimestamp(range.from), '', '包含开始时间'],
    ['查询范围', '结束', displayTimestamp(range.to), '', '不含结束时间'],
    ['查询范围', '时间粒度', range.granularity, '', seriesBucketDescription(range.granularity, report.dayOffset) + '；首尾可能是部分时段'],
    ['数据状态', '读取完成', displayTimestamp(fetchedAt), '', '两接口使用同一窗口，非数据库原子快照'],
    ['数据状态', '投影水位', displayTimestamp(dashboard.completeThrough), '', '当前租户dashboard投影完整水位'],
    ['数据状态', '水位延迟', dashboard.lag?.seconds ?? '未知', '秒', '接口读取时的延迟'],
    ['汇总', MEASUREMENT_EVENT_LABELS.revenue, dashboard.attributedRevenueMinor, '分', '事件金额汇总，待交易实付核验'],
    ['汇总', MEASUREMENT_EVENT_LABELS.cost, dashboard.fundingCostMinor, '分', '事件成本汇总，待资金账本核验'],
    ['汇总', MEASUREMENT_EVENT_LABELS.roi, displayMeasurementRoi(dashboard, '未知'), '比值', '（事件收入－事件补贴成本）÷事件补贴成本，成本为正时展示'],
    ...Object.entries(dashboard.counts).sort(([left], [right]) => left.localeCompare(right)).map(([type, count]) => ['事实计数', type, count, '条', '净投影计数']),
    ...points.flatMap(point => [
      ['时间序列', displayTimestamp(point.at), point.revenueMinor, '分', MEASUREMENT_EVENT_LABELS.revenue],
      ['时间序列', displayTimestamp(point.at), point.costMinor, '分', MEASUREMENT_EVENT_LABELS.cost],
      ['时间序列', displayTimestamp(point.at), point.conversions, '次', '转化数'],
    ]),
  ]
  return formatCsv(rows)
}

export function downloadReport(report: AnalyticsReport) {
  downloadCsv(reportCsv(report), `marketing-report-${report.range.to.replaceAll(':', '-')}.csv`)
}
