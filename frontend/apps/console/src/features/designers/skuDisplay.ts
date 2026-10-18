import type { BenefitSku } from '../../shared/api/schemas'

export function skuFaceValue(minor: number | null | undefined, currency: string | null | undefined) {
  if (minor == null) return '—'
  const amount = (minor / 100).toFixed(2)
  return `${currency || 'CNY'} ${amount}`
}

function formatInstant(value: string | null | undefined) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN')
}

export function validityLabel(sku: BenefitSku) {
  if (sku.validityType === 'RELATIVE') {
    return `领取后 ${sku.relativeDays ?? '—'} 天`
  }
  return `${formatInstant(sku.validFrom)} ~ ${formatInstant(sku.validTo)}`
}
