import { displayTimestamp } from '../../components/dateTime'
import { benefitSkuSchema, type BenefitSku } from '../../shared/api/schemas'

export function skuFaceValue(minor: number | null | undefined, currency: string | null | undefined) {
  if (minor == null) return '—'
  const amount = (minor / 100).toFixed(2)
  return `${currency || 'CNY'} ${amount}`
}

export function validityLabel(sku: BenefitSku) {
  if (sku.validityType === benefitSkuSchema.shape.validityType.enum.RELATIVE) {
    return `领取后 ${sku.relativeDays ?? '—'} 天`
  }
  return `${displayTimestamp(sku.validFrom)} 至 ${displayTimestamp(sku.validTo)}`
}
