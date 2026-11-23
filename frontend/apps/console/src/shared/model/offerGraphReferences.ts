import type { GraphDefinition } from '../api/schemas'

export function offerDiscountNodes(graph?: GraphDefinition) {
  return (graph?.nodes ?? []).filter(node => node.stableTypeId === 'offer.fixed' || node.stableTypeId === 'offer.percentage')
}

export function benefitReferences(graph?: GraphDefinition) {
  return [...new Set(offerDiscountNodes(graph).map(node => node.config.benefitDefinitionVersion?.trim()).filter(Boolean))]
}
