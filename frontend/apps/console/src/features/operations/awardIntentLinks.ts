import type { AwardIntentView } from '../../shared/api/schemas'
import { runtimeConfig } from '../../shared/config/runtime'

export function benefitOrderHref(item: AwardIntentView, origin = runtimeConfig.benefitConsoleOrigin) {
  if (!origin) return ''
  if (item.benefitOrderNo) {
    return `${origin}/orders?orderNo=${encodeURIComponent(item.benefitOrderNo)}`
  }
  if (item.status === 'DEAD') {
    const params = new URLSearchParams({ q: item.sourceRequestId, sourceSystem: item.sourceSystem })
    return `${origin}/orders?${params}`
  }
  return ''
}

export function riskDecisionHref(item: AwardIntentView, origin = runtimeConfig.riskConsoleOrigin) {
  if (!origin || item.status !== 'RISK_BLOCKED') return ''
  return `${origin}/decisions?q=${encodeURIComponent(item.sourceRequestId)}`
}
