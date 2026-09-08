import { describe, expect, it } from 'vitest'
import { offerCanvas } from './offerCanvas'

describe('offerCanvas', () => {
  it('never seeds the 双11 appliance graph', () => {
    const canvas = offerCanvas()
    expect(canvas.nodes.map((node) => node.data.label)).toEqual(['开始'])
    expect(canvas.edges).toEqual([])
    expect(canvas.problems).toEqual([])
    expect(canvas.nodes.some((node) => node.data.label.includes('家电') || node.data.label.includes('满 500'))).toBe(false)
  })
})
