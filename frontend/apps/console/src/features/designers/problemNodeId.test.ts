import { describe, expect, it } from 'vitest'
import { problemNodeId } from './problemNodeId'
import { fromGraphDefinition } from './graph'
const nodes = fromGraphDefinition({ definitionId: 'd', dialect: 'OFFER_DECISION_DAG', dialectVersion: '1.0.0', nodes: [
  { id: 'first', stableTypeId: 'offer.fixed', semanticVersion: '1.0.0', config: {} },
  { id: 'actual', stableTypeId: 'offer.fixed', semanticVersion: '1.0.0', config: {} },
], edges: [], variables: {}, annotations: {} }).nodes
const problem = { code: 'ERR', message: 'bad', severity: 'ERROR' as const, pointer: '/nodes' }
describe('problem targets', () => {
  it('uses an explicit node id and only valid array pointers as fallback', () => {
    expect(problemNodeId({ ...problem, nodeId: 'first', pointer: '/nodes/1/config' }, nodes)).toBe('first')
    expect(problemNodeId({ ...problem, pointer: '/nodes/1/config' }, nodes)).toBe('actual')
    expect(problemNodeId(problem, nodes)).toBeUndefined()
    expect(problemNodeId({ ...problem, nodeId: 'deleted' }, nodes)).toBeUndefined()
  })
})
