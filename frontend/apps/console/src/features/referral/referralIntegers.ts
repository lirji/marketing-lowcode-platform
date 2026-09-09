const SAFE_MAX = BigInt(Number.MAX_SAFE_INTEGER)
const SAFE_MIN = BigInt(Number.MIN_SAFE_INTEGER)

export type IntegerParse =
  | { ok: true; wire: string }
  | { ok: false; error: string }

function parseDigits(raw: string, allowNegative: boolean): IntegerParse {
  if (raw === '') return { ok: false, error: '不能为空' }
  if (raw !== raw.trim()) return { ok: false, error: '不能包含首尾空格' }
  const pattern = allowNegative ? /^-?(0|[1-9][0-9]*)$/ : /^(0|[1-9][0-9]*)$/
  if (!pattern.test(raw)) return { ok: false, error: '必须是整数，不能是小数、空串或科学计数' }
  let value: bigint
  try {
    value = BigInt(raw)
  } catch {
    return { ok: false, error: '超出整数范围' }
  }
  if (value > SAFE_MAX || value < SAFE_MIN) return { ok: false, error: '超出安全整数范围' }
  return { ok: true, wire: raw }
}

export function parseReferralInteger(raw: string, options?: {
  allowNegative?: boolean
  min?: bigint
  max?: bigint
}): IntegerParse {
  const parsed = parseDigits(raw, options?.allowNegative === true)
  if (!parsed.ok) return parsed
  const value = BigInt(parsed.wire)
  if (options?.min !== undefined && value < options.min) return { ok: false, error: `不能小于 ${options.min}` }
  if (options?.max !== undefined && value > options.max) return { ok: false, error: `不能大于 ${options.max}` }
  return parsed
}

const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/

export function parseReferralInstant(raw: string): IntegerParse {
  if (raw === '') return { ok: false, error: '不能为空' }
  if (!INSTANT.test(raw) || Number.isNaN(Date.parse(raw))) {
    return { ok: false, error: '必须是 UTC Instant，例如 2026-09-01T00:00:00Z' }
  }
  return { ok: true, wire: raw }
}

export function parseCurrencyCode(raw: string): IntegerParse {
  if (raw === '') return { ok: false, error: '不能为空' }
  if (!/^[A-Z]{3}$/.test(raw)) return { ok: false, error: '币种必须是 3 位大写字母' }
  return { ok: true, wire: raw }
}
