import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { Badge, Button, EmptyState, PageHeader, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { FieldHint } from '../../components/FieldHint'
import { RecordTable, RecordTime } from '../../components/RecordTable'
import { Select } from '../../components/Select'
import { api, ApiProblem } from '../../shared/api/client'
import { problemDetail } from '../../shared/api/problem'
import { releaseScopeKey } from '../../shared/api/useReleasePages'
import { type ReleaseBundle } from '../../shared/api/releaseBundles'
import { useAuth } from '../../shared/auth/useAuth'
import { bundleHttp } from './bundleErrors'
import { BundleCreate } from './BundleCreate'
import { BundleControls } from './BundleControls'

const positive = (raw: string) => /^[1-9]\d*$/.test(raw) && Number.isSafeInteger(Number(raw))

export function ReleaseBundlesPage() {
  const auth = useAuth()
  return <ReleaseBundles key={releaseScopeKey(auth)} />
}

function ReleaseBundles() {
  const auth = useAuth()
  const [params, setParams] = useSearchParams()
  const campaignId = (params.get('campaignId') ?? '').trim()
  const scope = releaseScopeKey(auth)
  const canRead = auth.authenticated && auth.hasPermission('release:read') && !api.demoMode
  const canList = canRead && auth.hasPermission('campaign:read')
  const campaigns = useQuery({ queryKey: ['bundle-campaigns', scope], queryFn: api.campaigns, enabled: canList, retry: false })
  const [manualCampaign, setManualCampaign] = useState('')
  const select = (newId: string) => setParams(newId ? { campaignId: newId } : {})
  const updateVersion = (version: number) => setParams({ campaignId, version: String(version) })
  return <div className="workspace bundle-page">
    <PageHeader eyebrow="发布中心" title="活动组合发布" description="核对活动的决策与旅程版本，整体生效、回滚或预约发布。" actions={<Link className="button secondary" to="/releases">查看发布清单</Link>} />
    {!canRead ? <EmptyState title={api.demoMode ? '组合发布需要连接服务' : '没有组合读取权限'} detail="请连接营销服务并使用具备发布读取权限的身份。" /> : <>
      <Panel>
        {canList ? <label className="field"><span>活动</span><Select aria-label="活动" aria-describedby="bundle-campaign-hint" value={campaignId} onChange={event => select(event.target.value)}>
          <option value="" disabled>选择活动</option>
          {campaignId && !campaigns.data?.some(item => item.id === campaignId) && <option value={campaignId}>{campaignId}</option>}
          {campaigns.data?.map(item => <option key={item.id} value={item.id}>{item.name} · {item.id}</option>)}
        </Select><FieldHint id="bundle-campaign-hint">仅显示当前身份可访问的活动，按活动查看组合版本。</FieldHint></label>
          : <form className="action-bar action-bar--fields bundle-inline" onSubmit={event => { event.preventDefault(); if (manualCampaign.trim()) select(manualCampaign.trim()) }}>
            <label className="field"><span>活动 ID</span><input aria-label="活动 ID" value={manualCampaign} maxLength={64} onChange={event => setManualCampaign(event.target.value)} aria-describedby="bundle-id-hint" /><FieldHint id="bundle-id-hint">填写要查看的业务活动编号。</FieldHint></label><Button type="submit" disabled={!manualCampaign.trim()}>查看活动组合</Button>
          </form>}
        {campaigns.isError && <><StateBanner tone="error" title="活动列表读取失败" detail={problemDetail(campaigns.error)} /><Button onClick={() => void campaigns.refetch()}>重试活动列表</Button></>}
      </Panel>
      {campaignId && <CampaignBundles key={JSON.stringify([scope, campaignId])} campaignId={campaignId} scope={scope} selected={params.get('version') ?? ''} onSelect={updateVersion} />}
    </>}
  </div>
}

function CampaignBundles({ campaignId, scope, selected, onSelect }: { campaignId: string; scope: string; selected: string; onSelect: (version: number) => void }) {
  const auth = useAuth()
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  const [creating, setCreating] = useState(false)
  const [createdBundle, setCreatedBundle] = useState<ReleaseBundle>()
  const [jump, setJump] = useState('')
  const latest = useQuery({ queryKey: ['bundle-latest', scope, campaignId], queryFn: async () => {
    try { return await api.latestBundle(campaignId) } catch (err) {
      if (err instanceof ApiProblem && err.status === bundleHttp.NOT_FOUND && err.code === 'RELEASE_BUNDLE_NOT_FOUND') return null
      throw err
    }
  }, retry: false })
  const head = latest.data?.version ?? 0
  const target = selected ? (positive(selected) ? Number(selected) : undefined) : head || undefined
  const historical = latest.isSuccess && target !== undefined && target < head
  const history = useQuery({ queryKey: ['bundle-version', scope, campaignId, target], enabled: historical,
    queryFn: () => api.bundleVersion(campaignId, target!), retry: false })
  const bundle = target === head ? latest.data : historical ? history.data : undefined
  const readError = latest.error ?? (historical ? history.error : null)
  return <>
    <Panel>
      <PanelHeader title="组合版本" aside={<Button disabled={!auth.hasPermission('release:write') || !latest.isSuccess || latest.isFetching || creating} onClick={() => setCreating(true)}>创建组合版本</Button>} />
      {createdBundle && createdBundle.bundleId === bundle?.bundleId && <StateBanner tone="success" title={`组合 v${createdBundle.version} 已创建`} detail="可以准备运行端，核对就绪回执后整体发布。" />}
      {latest.isLoading && <p role="status">正在读取组合版本…</p>}
      {readError && <><StateBanner tone="error" title="组合版本读取失败" detail={problemDetail(readError)} /><Button onClick={() => void (latest.isError ? latest.refetch() : history.refetch())}>重新读取版本</Button></>}
      {latest.isSuccess && head === 0 && <EmptyState title="当前活动暂无组合版本" detail="暂存本活动的发布清单后，可创建首个组合版本。" />}
      {latest.isSuccess && head > 0 && <>
        <form className="action-bar action-bar--fields bundle-inline" onSubmit={event => { event.preventDefault(); if (positive(jump) && Number(jump) <= head) { onSelect(Number(jump)); setJump('') } }}>
          <label className="field"><span>查看历史版本</span><input type="number" aria-label="组合版本号" min={1} max={head} value={jump} placeholder={String(target ?? head)} onChange={event => setJump(event.target.value)} aria-describedby="bundle-version-hint" /><FieldHint id="bundle-version-hint">输入 1 至 {head} 之间的版本号，最新为 v{head}。</FieldHint></label>
          <Button type="submit" disabled={!positive(jump) || Number(jump) > head}>查看版本</Button>
          {target !== head && <Button onClick={() => onSelect(head)}>查看最新版本</Button>}
        </form>
        {selected && (!target || target > head) && <StateBanner tone="warn" title="版本号超出已创建范围" detail={`请选择 v1 至 v${head}。`} />}
        {history.isLoading && <p role="status">正在读取历史组合…</p>}
      </>}
      {bundle && !readError && <dl className="bundle-facts">
        <div><dt>当前查看</dt><dd>v{bundle.version}</dd></div><div><dt>组合 ID</dt><dd>{bundle.bundleId}</dd></div>
        <div><dt>环境</dt><dd>{bundle.environment}</dd></div><div><dt>部署单元</dt><dd>{bundle.cell}</dd></div>
        <div><dt>创建人</dt><dd>{bundle.createdBy}</dd></div><div><dt>创建时间（北京）</dt><dd><RecordTime value={bundle.createdAt} /></dd></div>
      </dl>}
    </Panel>
    {creating && <BundleCreate campaignId={campaignId} head={head} canSubmit={latest.isSuccess && !latest.isFetching}
      reload={async () => { await latest.refetch() }} onCancel={() => setCreating(false)} onCreated={async value => { if (!mounted.current) return; setCreating(false); setCreatedBundle(value); await latest.refetch(); if (mounted.current) onSelect(value.version) }} />}
    {bundle && !readError && <BundleDetails key={bundle.bundleId} bundle={bundle} scope={scope} />}
  </>
}

function BundleDetails({ bundle, scope }: { bundle: ReleaseBundle; scope: string }) {
  const auth = useAuth()
  const ready = useQuery({ queryKey: ['bundle-readiness', scope, bundle.bundleId], queryFn: () => api.bundleReadiness(bundle), retry: false })
  const prepare = useMutation({ mutationFn: () => api.bundleReadiness(bundle, true), onSettled: () => ready.refetch() })
  const evidence = !ready.isError && ready.data ? new Map(ready.data.members.map(item => [item.manifestId, item])) : undefined
  return <>
    <Panel><PanelHeader title="成员与运行就绪" aside={<div className="action-bar bundle-actions"><Button disabled={ready.isFetching || prepare.isPending} onClick={() => void ready.refetch()}>刷新就绪</Button>
      {auth.hasPermission('release:activate') && <Button disabled={prepare.isPending} onClick={() => prepare.mutate()}>{prepare.isPending ? '正在准备…' : '准备运行端'}</Button>}</div>} />
      {ready.isError && <StateBanner tone="error" title="就绪状态读取失败" detail={problemDetail(ready.error)} />}
      {prepare.isError && <StateBanner tone="error" title="运行端准备失败" detail={problemDetail(prepare.error)} />}
      {ready.isLoading && <p role="status">正在读取各成员回执…</p>}
      {evidence && ready.data && <p><Badge tone={ready.data.runtimeReady ? 'good' : 'warn'}>{ready.data.runtimeReady ? '成员已就绪' : '等待成员就绪'}</Badge> 检查时间（北京时间）<RecordTime value={ready.data.checkedAt} /> 组合有效期至（北京时间）<RecordTime value={ready.data.expiresAt} /></p>}
      <RecordTable label="组合成员" columns={['清单 ID', '运行类型', '命名空间', '代际', '就绪副本', '就绪容量', '忽略证据', '就绪状态']}>
        {bundle.members.map(member => { const ack = evidence?.get(member.manifestId)
          return <tr key={member.manifestId}><td>{member.manifestId}</td><td>{member.runtime}</td><td>{member.namespace}</td><td>{member.generation}</td>
            <td>{ack?.readyReplicas ?? '—'}</td><td>{ack?.readyCapacity ?? '—'}</td><td>{ack?.ignoredEvidence ?? '—'}</td><td><Badge tone={ack?.runtimeReady ? 'good' : 'warn'}>{ack ? ack.runtimeReady ? '已就绪' : '待就绪' : '待核对'}</Badge></td></tr> })}
      </RecordTable>
    </Panel>
    <BundleControls bundle={bundle} scope={scope} ready={ready.isSuccess && !ready.isFetching && ready.data.runtimeReady && !prepare.isPending} />
  </>
}
