import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { api, ApiProblem } from '../../shared/api/client'
import { BundleMemberLimit, type ReleaseBundle } from '../../shared/api/releaseBundles'
import { type ReleaseView, ReleaseState } from '../../shared/api/schemas'
import { bundleHttp, uncertainWrite } from './bundleErrors'
import { problemDetail } from '../../shared/api/problem'
import { Button, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { FieldHint } from '../../components/FieldHint'
import { RecordTable } from '../../components/RecordTable'

type Prepare = { expectedVersion: number; manifestIds: string[]; key: string }
const memberId = /^[A-Za-z0-9_.:-]{1,64}$/

function selectionProblem(releases: ReleaseView[]) {
  const slots = new Set<string>()
  const target = releases[0].manifest
  for (const { manifest, state } of releases) {
    if (state !== ReleaseState.STAGED || manifest.canaryBasisPoints !== 0) return '请选择已暂存、全量发布的清单。'
    if (!manifest.namespace || !manifest.environment || !manifest.runtime || !['decision', 'journey'].includes(manifest.runtime)) return '成员需要完整的 Decision 或 Journey 发布位置。'
    if (manifest.cell !== target.cell || manifest.environment !== target.environment) return '各成员必须属于相同环境和部署单元。'
    const slot = JSON.stringify([manifest.runtime, manifest.namespace])
    if (slots.has(slot)) return '同一运行类型和命名空间只能选一个版本。'
    slots.add(slot)
  }
  return ''
}

export function BundleCreate({ campaignId, head, canSubmit, reload, onCancel, onCreated }: {
  campaignId: string; head: number; canSubmit: boolean; reload: () => Promise<void>; onCancel: () => void; onCreated: (value: ReleaseBundle) => Promise<void>
}) {
  const [rawIds, setRawIds] = useState('')
  const ids = rawIds.trim().split(/\s+/).filter(Boolean)
  const valid = ids.length >= BundleMemberLimit.MIN && ids.length <= BundleMemberLimit.MAX && new Set(ids).size === ids.length && ids.every(id => memberId.test(id))
  const preview = useMutation({ mutationFn: (selected: string[]) => Promise.all(selected.map(id => api.getRelease(id))) })
  const create = useMutation({
    mutationFn: ({ expectedVersion, manifestIds, key }: Prepare) => api.prepareBundle(campaignId, expectedVersion, manifestIds, key),
    onSuccess: onCreated,
    onError: async error => { if (error instanceof ApiProblem && error.status === bundleHttp.CONFLICT) await reload() },
  })
  const uncertain = create.isError && uncertainWrite(create.error)
  const locked = preview.isPending || create.isPending || uncertain
  const problem = preview.data && selectionProblem(preview.data)
  return <Panel><PanelHeader title="创建不可变组合" />
    <label className="field"><span>成员清单 ID</span><textarea aria-label="成员清单 ID" aria-describedby="bundle-members-hint" value={rawIds} disabled={locked} onChange={event => { setRawIds(event.target.value); preview.reset(); create.reset() }} />
      <FieldHint id="bundle-members-hint">从编译暂存结果复制本活动的 2–8 个清单 ID，每行一个。控制服务在创建时复核归属和审批。</FieldHint></label>
    {rawIds && !valid && <p>请填写 2–8 个不同的有效清单 ID。</p>}
    <div className="action-bar bundle-actions"><Button disabled={!valid || locked} onClick={() => preview.mutate(ids)}>核对清单</Button></div>
    {preview.isError && <StateBanner tone="error" title="清单核对失败" detail={problemDetail(preview.error)} />}
    {preview.data && <RecordTable label="待组合清单" columns={['清单 ID', '环境', '部署单元', '运行类型', '命名空间', '代际', '定义 ID', '定义版本']}>
      {preview.data.flatMap(({ manifest }) => manifest.artifacts.map(artifact => <tr key={JSON.stringify([manifest.manifestId, artifact.artifactId])}><td>{manifest.manifestId}</td>
        <td>{manifest.environment}</td><td>{manifest.cell}</td><td>{manifest.runtime}</td><td>{manifest.namespace}</td><td>{manifest.generation}</td>
        <td>{artifact.definitionId}</td><td>{artifact.definitionVersion}</td></tr>))}</RecordTable>}
    {problem && <StateBanner tone="warn" title="成员条件未满足" detail={problem} />}
    {create.isError && <StateBanner tone={uncertain ? 'warn' : 'error'} title={uncertain ? '创建结果待确认' : '创建组合失败'} detail={problemDetail(create.error)} />}
    <footer className="action-bar bundle-actions">
      {uncertain && create.variables ? <Button tone="primary" onClick={() => create.mutate(create.variables!)}>用原请求确认创建结果</Button> :
        <Button tone="primary" disabled={!canSubmit || !valid || !preview.isSuccess || !!problem || locked} onClick={() => create.mutate({ expectedVersion: head, manifestIds: ids, key: crypto.randomUUID() })}>确认创建 v{head + 1}</Button>}
      <Button disabled={create.isPending || preview.isPending} onClick={onCancel}>关闭创建表单</Button>
    </footer>
  </Panel>
}
