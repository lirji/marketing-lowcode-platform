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

/** 普通展示区明确标出业务时区，缺失或无效时间不回显接口原串。 */
export function displayTimestamp(value?: string | null) {
  if (!value) return '—'
  if (!Number.isFinite(Date.parse(value))) return '时间无效'
  return `${displayInstant(value)}（北京时间）`
}

/** 统计“今天”从北京零点起算，避免海外电脑把查询窗口错移一天。 */
export function businessDayStart(now = new Date()) {
  const shifted = new Date(now.getTime() + BUSINESS_OFFSET_MS)
  return new Date(Date.UTC(shifted.getUTCFullYear(), shifted.getUTCMonth(), shifted.getUTCDate()) - BUSINESS_OFFSET_MS)
}

export function displayTimeTick(value: string, granularity: 'hour' | 'day') {
  if (!Number.isFinite(Date.parse(value))) return '时间无效'
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: BUSINESS_TIME_ZONE, hourCycle: 'h23',
    ...(granularity === 'hour' ? { hour: '2-digit', minute: '2-digit' } : { month: '2-digit', day: '2-digit' }),
  }).format(new Date(value))
}

/** 只用于诊断内容展示；不修改原对象、草稿、请求或下载数据。 */
export function displayDiagnosticJson(value: unknown) {
  return JSON.stringify(value, (_key, item: unknown) => typeof item === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(item) ? displayTimestamp(item) : item, 2)
}
