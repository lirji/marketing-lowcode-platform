import type { DefinitionBundle, ReleaseView } from '../../shared/api/schemas'

/**
 * 只接受制品来源与当前活动冻结版本完全一致的 Release，避免被同租户其他活动的发布误判为就绪。
 */
export function definitionsHaveRelease(definitions: Array<DefinitionBundle | undefined>, releases: ReleaseView[]): boolean {
  const required = definitions.filter((item): item is DefinitionBundle => Boolean(item))
  return required.length === definitions.length && required.every((definition) => releases.some((release) =>
    (release.state === 'STAGED' || release.state === 'ACTIVE')
    && release.manifest.artifacts.some((artifact) => artifact.definitionId === definition.definitionId && artifact.definitionVersion === definition.version),
  ))
}
