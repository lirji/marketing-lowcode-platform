/** 测量服务以 Unix 纪元表示水位未知。窗口使用 [from, to)，水位到达 to 即完整。 */
export function measurementCoverage(from: string, to: string, completeThrough?: string | null) {
  const watermark = Date.parse(completeThrough ?? '')
  if (!Number.isFinite(watermark) || watermark <= 0) return 'unknown'
  if (watermark <= Date.parse(from)) return 'uncovered'
  return watermark < Date.parse(to) ? 'partial' : 'complete'
}
