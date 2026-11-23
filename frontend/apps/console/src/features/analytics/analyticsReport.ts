import { businessDayStart, displayTimestamp } from '../../components/dateTime'
import type { DashboardMetrics, SeriesPoint } from '../../shared/api/schemas'
import { measurementCoverage } from '../../shared/model/measurementCoverage'
import { displayMeasurementRoi } from '../../shared/model/measurementRoi'

export const ANALYTICS_PERIODS = { TODAY: '今天', WEEK: '7 天', MONTH: '30 天' } as const
export type AnalyticsPeriod = keyof typeof ANALYTICS_PERIODS
export type AnalyticsRange = { from: string; to: string; granularity: 'hour' | 'day' }
export type AnalyticsReport = { tenantId: string; campaignId?: string; range: AnalyticsRange; dashboard: DashboardMetrics; points: SeriesPoint[]; fetchedAt: string }
const REPORT_VALUE_ERROR = '报告包含超出精确范围的金额或计数，无法可靠显示或导出'
const DAY_MS = 24 * 60 * 60 * 1000
export const MAX_ANALYTICS_RANGE_MS = 366 * DAY_MS

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

/** CSV中的外部字段按文本输出，避免计数名称等内容被表格软件解释为公式。 */
function csvCell(value: string | number) {
  let text = String(value)
  const firstVisible = [...text].find(character => character.charCodeAt(0) > 0x1f && character.trim())
  if (typeof value === 'string' && firstVisible && '=+-@'.includes(firstVisible)) text = "'" + text
  return '"' + text.replaceAll('"', '""') + '"'
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
    ['查询范围', '活动', report.campaignId || '全部活动', '', '精确活动ID；无归因策略筛选'],
    ['查询范围', '开始', displayTimestamp(range.from), '', '包含开始时间'],
    ['查询范围', '结束', displayTimestamp(range.to), '', '不含结束时间'],
    ['查询范围', '时间粒度', range.granularity, '', range.granularity === 'day' ? '后端UTC自然日分桶；首尾可能是不完整桶' : '按小时分桶'],
    ['数据状态', '读取完成', displayTimestamp(fetchedAt), '', '两接口使用同一窗口，非数据库原子快照'],
    ['数据状态', '投影水位', displayTimestamp(dashboard.completeThrough), '', '当前租户dashboard投影完整水位'],
    ['数据状态', '水位延迟', dashboard.lag?.seconds ?? '未知', '秒', '接口读取时的延迟'],
    ['汇总', '归因收入', dashboard.attributedRevenueMinor, '分', '接口返回值'],
    ['汇总', '补贴成本', dashboard.fundingCostMinor, '分', '接口返回值'],
    ['汇总', 'ROI', displayMeasurementRoi(dashboard, '未知'), '比值', '净补贴成本为正时展示接口比值'],
    ...Object.entries(dashboard.counts).sort(([left], [right]) => left.localeCompare(right)).map(([type, count]) => ['事实计数', type, count, '条', '净投影计数']),
    ...points.flatMap(point => [
      ['时间序列', displayTimestamp(point.at), point.revenueMinor, '分', '归因收入'],
      ['时间序列', displayTimestamp(point.at), point.costMinor, '分', '补贴成本'],
      ['时间序列', displayTimestamp(point.at), point.conversions, '次', '转化数'],
    ]),
  ]
  return '\uFEFF' + rows.map(row => row.map(csvCell).join(',')).join('\r\n') + '\r\n'
}

export function downloadReport(report: AnalyticsReport) {
  const url = URL.createObjectURL(new Blob([reportCsv(report)], { type: 'text/csv;charset=utf-8' }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `marketing-report-${report.range.to.replaceAll(':', '-')}.csv`
  document.body.append(anchor)
  anchor.click()
  anchor.remove()
  // 下载消费者可能异步读取Blob，稍后释放而不是在click同步栈里失效。
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}
