const OUTPUT_PORTS: Record<string, readonly string[]> = {
  'offer.start': ['next'], 'offer.fixed': ['next'], 'offer.percentage': ['next'],
  'offer.condition': ['true', 'false'], 'offer.end': [],
  'journey.trigger': ['next'], 'journey.condition': ['true', 'false'],
  'journey.wait-event': ['matched'], 'journey.wait-timer': ['elapsed'],
  'journey.send': ['next'], 'journey.grant': ['next'], 'journey.webhook': ['next'],
  'journey.repeat': ['repeat', 'exit'], 'journey.end': [],
}

/** 端口对应服务端节点契约，颜色只用于显示，不能决定路由。 */
export function nodePorts(type: string) {
  return {
    inputs: type === 'offer.start' || type === 'journey.trigger' ? [] : ['in'],
    outputs: OUTPUT_PORTS[type] ?? ['next'],
  }
}
