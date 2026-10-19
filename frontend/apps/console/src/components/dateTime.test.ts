import { describe, expect, it } from 'vitest'
import { businessDayStart, displayDiagnosticJson, displayTimeTick, displayTimestamp } from './dateTime'

// 测试进程也会以非中国时区运行，防止展示或统计范围偷偷依赖本机时区。
describe('Beijing time presentation', () => {
  it('converts UTC and explicit offsets across day/year boundaries', () => {
    expect(displayTimestamp('2026-09-16T15:28:22.559Z')).toBe('2026/09/16 23:28:22（北京时间）')
    expect(displayTimestamp('2026-12-31T20:15:20.123456789Z')).toBe('2027/01/01 04:15:20（北京时间）')
    expect(displayTimestamp('2027-01-01T04:15:20+08:00')).toBe('2027/01/01 04:15:20（北京时间）')
    expect(displayTimestamp('2026-12-31T15:15:20-05:00')).toBe('2027/01/01 04:15:20（北京时间）')
  })

  it('does not show an input placeholder or raw invalid timestamp for missing records', () => {
    expect(displayTimestamp(null)).toBe('—')
    expect(displayTimestamp('')).toBe('—')
    expect(displayTimestamp('invalid')).toBe('时间无效')
  })

  it('uses Beijing midnight for today while preserving the UTC API instant', () => {
    expect(businessDayStart(new Date('2026-12-31T15:59:59Z')).toISOString()).toBe('2026-12-30T16:00:00.000Z')
    expect(businessDayStart(new Date('2026-12-31T16:00:00Z')).toISOString()).toBe('2026-12-31T16:00:00.000Z')
  })

  it('uses the same business timezone for chart axes', () => {
    expect(displayTimeTick('2026-12-31T16:00:00Z', 'hour')).toBe('00:00')
    expect(displayTimeTick('2026-12-31T16:00:00Z', 'day')).toBe('01/01')
    expect(displayTimeTick('invalid', 'hour')).toBe('时间无效')
  })

  it('formats nested diagnostic timestamps without mutating source data or identifiers', () => {
    const source = { expiresAt: '2026-12-31T20:15:20.123456789Z', nested: [{ id: 'rule-2026-12-31', at: '2027-01-01T04:15:20+08:00' }], count: 1 }
    const before = JSON.stringify(source)
    expect(displayDiagnosticJson(source)).toContain('2027/01/01 04:15:20（北京时间）')
    expect(displayDiagnosticJson(source)).toContain('rule-2026-12-31')
    expect(JSON.stringify(source)).toBe(before)
  })
})
