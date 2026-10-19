// 本控制台业务时间统一按北京时间展示，接口仍传UTC Instant；不依赖操作者电脑时区。
export const BUSINESS_TIME_ZONE = 'Asia/Shanghai'
const BUSINESS_OFFSET_MS = 8 * 60 * 60 * 1000
export function instantParts(value: string) {
  const parsed = Date.parse(value)
  if (!Number.isFinite(parsed)) return undefined
  const shifted = new Date(parsed + BUSINESS_OFFSET_MS)
  return { day: new Date(shifted.getUTCFullYear(), shifted.getUTCMonth(), shifted.getUTCDate()), hour: String(shifted.getUTCHours()).padStart(2, '0'), minute: String(shifted.getUTCMinutes()).padStart(2, '0'), second: String(shifted.getUTCSeconds()).padStart(2, '0'), millisecond: shifted.getUTCMilliseconds() }
}
export function toInstant(day: Date, hour: string, minute: string, second: string, millisecond = 0) {
  return new Date(Date.UTC(day.getFullYear(), day.getMonth(), day.getDate(), Number(hour), Number(minute), Number(second), millisecond) - BUSINESS_OFFSET_MS).toISOString()
}
export function displayInstant(value: string) {
  if (!value) return '请选择日期和时间'
  if (!Number.isFinite(Date.parse(value))) return '时间无效，请重新选择'
  return new Intl.DateTimeFormat('zh-CN', { timeZone: BUSINESS_TIME_ZONE, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(new Date(value))
}

/** 已存时间可能带微秒/纳秒；未修改时保持原串，调整日期或时分秒时也保留其小数精度。 */
export function preserveInstantPrecision(next: string, original: string) {
  if (Date.parse(next) === Date.parse(original)) return original
  const fraction = original.match(/\.(\d{1,9})Z$/)?.[1]
  return fraction ? next.replace(/\.\d{3}Z$/, `.${fraction}Z`) : next
}
