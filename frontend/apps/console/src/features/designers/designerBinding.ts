/** 绑定活动后的设计器标题：用活动名，不要回落到双11/加购演示文案。 */
export function designerHeading(input: {
  campaignId?: string
  campaignName?: string
  boundSuffix: string
  demoTitle: string
  demoVersion: string
}): { title: string; version: string; bound: boolean } {
  const bound = Boolean(input.campaignId?.trim())
  if (!bound) {
    return { title: input.demoTitle, version: input.demoVersion, bound: false }
  }
  const name = input.campaignName?.trim()
  return {
    title: name ? `${name} · ${input.boundSuffix}` : `${input.boundSuffix} 草稿`,
    version: '尚未保存',
    bound: true,
  }
}

export function campaignNameFromLocationState(state: unknown): string | undefined {
  if (!state || typeof state !== 'object' || !('campaignName' in state)) return undefined
  const name = (state as { campaignName?: unknown }).campaignName
  return typeof name === 'string' && name.trim() ? name.trim() : undefined
}
