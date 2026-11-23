import { describe, expect, it } from 'vitest'
import { analyticsRange, displayMinor, reportCsv, type AnalyticsReport } from './analyticsReport'

describe('analytics report values', () => {
  it('uses Beijing midnight and one captured end; rolling windows preserve the same instant', () => {
    const now = new Date('2026-11-17T03:04:05Z')
    expect(analyticsRange('TODAY', now)).toEqual({ from: '2026-11-16T16:00:00.000Z', to: now.toISOString(), granularity: 'hour' })
    expect(analyticsRange('WEEK', now).from).toBe('2026-11-10T03:04:05.000Z')
    expect(analyticsRange('MONTH', now).from).toBe('2026-10-18T03:04:05.000Z')
  })
  it('preserves cents and corrections without rounding and refuses imprecise integers', () => {
    expect(displayMinor(12345)).toBe('123.45')
    expect(displayMinor(-1)).toBe('-0.01')
    expect(displayMinor(Number.MAX_SAFE_INTEGER)).toBe('90071992547409.91')
    expect(displayMinor(Number.MAX_SAFE_INTEGER + 1)).toBe('金额超出精确显示范围')
  })
  it('exports all returned points, metadata and raw cents with CSV escaping and formula protection', () => {
    const report: AnalyticsReport = { tenantId: 'report-tenant', range: analyticsRange('WEEK', new Date('2026-11-17T03:04:05Z')), fetchedAt: '2026-11-17T03:04:06Z',
      dashboard: { counts: { '\t=HYPERLINK("unsafe")': 2, 'A,"B"\nC': -1 }, attributedRevenueMinor: 12345, fundingCostMinor: 67, roi: '1.2345', completeThrough: '2026-11-17T03:04:05Z', lag: { seconds: 3600 } },
      points: [{ at: '2026-11-16T00:00:00Z', revenueMinor: 10000, costMinor: 66, conversions: 1 }, { at: '2026-11-17T00:00:00Z', revenueMinor: 2345, costMinor: 1, conversions: 2 }] }
    const csv = reportCsv(report)
    expect(csv.startsWith('\uFEFF')).toBe(true)
    expect(csv).toContain('"\'\t=HYPERLINK(""unsafe"")"')
    expect(csv).toContain('"A,""B""\nC","-1"')
    expect(csv).toContain('"归因收入","12345","分"')
    expect(csv.match(/"时间序列"/g)).toHaveLength(6)
    expect(csv).toContain('北京时间')
    expect(csv).toContain('非数据库原子快照')
    expect(csv).toContain('后端UTC自然日分桶')
    expect(csv).not.toContain('¥ 18.43M')
    expect(() => reportCsv({ ...report, dashboard: { ...report.dashboard, completeThrough: '2026-11-17T02:00:00Z' } })).toThrow('所选范围的数据尚未齐全')
    expect(() => reportCsv({ ...report, dashboard: { ...report.dashboard, attributedRevenueMinor: 2 ** 53 } })).toThrow('无法可靠显示或导出')
  })
})
