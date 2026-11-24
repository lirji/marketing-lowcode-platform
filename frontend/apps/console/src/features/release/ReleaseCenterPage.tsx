import { Link } from 'react-router-dom'
import { RecordTable, RecordTime } from '../../components/RecordTable'
import { Select } from '../../components/Select'
import { FieldHint } from '../../components/FieldHint'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Ban, PackageCheck, RotateCcw, Rocket, ShieldAlert } from 'lucide-react'
import { Badge, Button, DemoBanner, EmptyState, Modal, PageHeader, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { problemDetail } from '../../shared/api/problem'
import { api, ApiProblem } from '../../shared/api/client'
import { useAuth } from '../../shared/auth/useAuth'
import { ReleaseState, type ReleaseView, type ReleaseStatus } from '../../shared/api/schemas'
import { ReleaseWizard } from './ReleaseWizard'
import { releaseScopeKey, useReleasePages } from '../../shared/api/useReleasePages'

type ConfirmKind = 'activate' | 'rollback' | 'kill'
const NETWORK_FAILURE_STATUS = 0

export function ReleaseCenterPage() {
  const auth = useAuth()
  return <ReleaseCenter key={releaseScopeKey(auth)} />
}

function ReleaseCenter() {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const [confirm, setConfirm] = useState<ConfirmKind | null>(null)
  const [reason, setReason] = useState('')
  const [checked, setChecked] = useState(false)
  const [result, setResult] = useState('')
  const [wizardOpen, setWizardOpen] = useState(false)
  const [actionTarget, setActionTarget] = useState<ReleaseView>()
  const [activationKey, setActivationKey] = useState(() => crypto.randomUUID())
  const [targetGeneration, setTargetGeneration] = useState('')
  const [releaseStatus, setReleaseStatus] = useState<ReleaseStatus>()
  const releases = useReleasePages(releaseStatus)
  const latest = releases.items[0]
  const candidates = useQuery({ queryKey: ['rollback-candidates', releaseScopeKey(auth), actionTarget?.manifest.manifestId],
    queryFn: () => api.rollbackCandidates(actionTarget!.manifest.manifestId),
    enabled: confirm === 'rollback' && !!actionTarget && auth.hasPermission('release:read') && !api.demoMode,
    retry: false, staleTime: 0,
  })
  const activate = useMutation({
    mutationFn: ({ manifestId, idempotencyKey }: { manifestId: string; idempotencyKey: string }) => api.activateRelease(manifestId, idempotencyKey),
    onSuccess: (value) => { setResult(`灰度/激活已提交 ${value.manifest.manifestId} · ${value.state}`); closeConfirm() },
  })
  const rollback = useMutation({
    mutationFn: ({ manifestId, generation, idempotencyKey }: { manifestId: string; generation: number; idempotencyKey: string }) => api.rollbackRelease(manifestId, generation, idempotencyKey),
    onSuccess: (value) => { setResult(`回滚已提交 ${value.manifest.manifestId}`); closeConfirm() },
  })
  const kill = useMutation({
    mutationFn: ({ namespace, reason, idempotencyKey }: { namespace: string; reason: string; idempotencyKey: string }) => api.setKillSwitch(namespace, true, reason, idempotencyKey),
    onSuccess: (value) => { setResult(`Kill switch 已启用 ${value.namespace} · seq ${value.sequence ?? 0}`); closeConfirm() },
  })
  const closeConfirm = useCallback(() => { setConfirm(null); setActionTarget(undefined); setReason(''); setChecked(false); setTargetGeneration(''); void queryClient.invalidateQueries({ queryKey: ['releases'] }) }, [queryClient])
  const openConfirm = (kind: ConfirmKind, target: ReleaseView) => {
    if (pending) return
    activate.reset(); rollback.reset(); kill.reset(); setChecked(false); setReason(''); setTargetGeneration('')
    setActionTarget(target)
    setActivationKey(crypto.randomUUID())
    setConfirm(kind)
  }
  const actionError = activate.error ?? rollback.error ?? kill.error
  const pending = activate.isPending || rollback.isPending || kill.isPending
  const pendingRef = useRef(false)
  useEffect(() => { pendingRef.current = pending }, [pending])
  // 保持回调身份稳定，避免Modal在每次输入字符时重设初始焦点。
  const dismissConfirm = useCallback(() => { if (!pendingRef.current) closeConfirm() }, [closeConfirm])
  const rollbackUncertain = rollback.isError && (!(rollback.error instanceof ApiProblem) || rollback.error.status === NETWORK_FAILURE_STATUS || rollback.error.status >= 500)
  const canRetryRollback = rollbackUncertain && !!rollback.variables && checked && !pending && !api.demoMode && auth.hasPermission('release:rollback')
  const chosen = candidates.data?.find((item) => String(item.generation) === targetGeneration)
  const requiredPermission = confirm === 'kill' ? 'release:kill-switch' : confirm === 'rollback' ? 'release:rollback' : 'release:activate'
  const canExecute = Boolean(actionTarget) && checked && auth.hasPermission(requiredPermission) && !pending && !api.demoMode
    && (confirm !== 'kill' || reason.trim().length >= 4)
    && (confirm !== 'rollback' || (!rollbackUncertain && auth.hasPermission('release:read') && !!chosen && !candidates.isFetching && !candidates.isError))
  const wizardMissingPermissions = ['definition:read', 'definition:compile', 'artifact:read', 'release:write', 'release:read']
    .filter((permission) => !auth.hasPermission(permission))
  const canOpenWizard = wizardMissingPermissions.length === 0

  return <div className="workspace release-page">
    <PageHeader eyebrow="RELEASE CENTER" title="发布中心" description="在对应清单行选择操作，核对环境、运行类型、命名空间和实际回滚目标后再提交。" actions={<>
      <Link className="button primary" to="/releases/bundles">活动组合发布</Link>
      <Button onClick={() => setWizardOpen(true)} disabled={api.demoMode || !canOpenWizard} title={api.demoMode ? '演示模式不编译' : canOpenWizard ? '选择批准版本并编译暂存' : `缺少 ${wizardMissingPermissions.join('、')}`}><PackageCheck size={15} />编译并暂存</Button>
    </>} />
    <DemoBanner />
    {!api.demoMode && !releases.canRead && <StateBanner tone="warn" title="没有发布读取权限" detail="需要 release:read 权限才能查看当前范围的发布清单。" />}
    {!api.demoMode && releases.canRead && <div className="action-bar action-bar--fields toolbar"><label className="field"><span>发布状态</span><Select aria-label="发布状态" aria-describedby="release-state-hint" value={releaseStatus ?? ''} disabled={pending} onChange={(event) => setReleaseStatus((event.target.value || undefined) as ReleaseStatus | undefined)}><option value="">全部状态</option><option value={ReleaseState.STAGED}>已暂存</option><option value={ReleaseState.ACTIVE}>已激活</option><option value={ReleaseState.ROLLED_BACK}>已回滚</option><option value={ReleaseState.REVOKED}>已撤销</option></Select><FieldHint id="release-state-hint">按当前组织和门店范围在服务端筛选，按清单创建时间由新到旧加载。</FieldHint></label><Button disabled={pending || releases.isFetching} onClick={() => void releases.refresh()}>刷新发布清单</Button></div>}
    {!api.demoMode && releases.canRead && <p>已加载 {releases.items.length} 条发布清单{releases.hasNextPage ? '，还有更多' : ''}</p>}
    {!api.demoMode && releases.canRead && releases.isLoading && <StateBanner tone="info" title="正在加载发布清单" detail="正在读取当前权限范围内的记录。" />}

    {result && <StateBanner tone="success" title="发布操作已登记" detail={result} />}
    {releases.isError && !releases.isFetchNextPageError && <StateBanner tone="error" title="发布列表加载失败" detail={problemDetail(releases.error)} />}
    {actionError && !confirm && <StateBanner tone="error" title="发布操作失败" detail={problemDetail(actionError)} />}
    {!api.demoMode && releases.canRead && releases.items.length === 0 && !releases.isLoading && !releases.isError ? <EmptyState title="当前筛选没有发布清单" detail="可切换发布状态或刷新列表；新清单通过已批准定义编译并暂存后可见。" action={<Button tone="primary" onClick={() => setWizardOpen(true)} disabled={!canOpenWizard} title={canOpenWizard ? '选择批准版本并编译暂存' : `缺少 ${wizardMissingPermissions.join('、')}`}><PackageCheck size={15} />编译并暂存</Button>} /> : null}
    {latest && <div className="release-summary"><Panel><p>当前筛选首项</p><strong>{latest.manifest.manifestId.slice(0, 12)}</strong><small>{latest.state}</small></Panel><Panel><p>规则制品</p><strong>{latest.manifest.artifacts.length}</strong><small>当前筛选首项的制品数量</small></Panel><Panel><p>就绪副本</p><strong>{latest.readyReplicas}</strong><small>容量 {latest.readyCapacity}</small></Panel><Panel><p>清单流量配置</p><strong>{((latest.manifest.canaryBasisPoints ?? 0) / 100).toFixed(0)}%</strong><small>canary basis points</small></Panel></div>}
    <Panel>
      <PanelHeader eyebrow="CELL READINESS" title="运行时就绪矩阵" />
      {api.demoMode ? <EmptyState title="演示模式不展示真实发布槽" detail="关闭 DEMO_MODE 后读取 /api/v1/releases。" /> : <RecordTable label="运行时就绪矩阵" columns={['发布清单 ID', '环境', '部署单元', '运行类型', '命名空间', '状态', '发布代际', '就绪副本', '声明容量', '签名', '流量比例', '操作']} actions>{releases.items.map((item) => <tr key={item.manifest.manifestId}><td>{item.manifest.manifestId}</td><td>{item.manifest.environment ?? '—'}</td><td>{item.manifest.cell}</td><td>{item.manifest.runtime}</td><td>{item.manifest.namespace ?? '—'}</td><td><Badge tone={item.state === ReleaseState.ACTIVE ? 'good' : 'warn'}>{item.state}</Badge></td><td>{item.manifest.generation}</td><td>{item.readyReplicas}</td><td>{item.readyCapacity}</td><td>{item.manifest.signature ? '已签名' : '未签名'}</td><td>{((item.manifest.canaryBasisPoints ?? 0) / 100).toFixed(2)}%</td><td><div className="release-row-actions"><Button aria-label={`激活 ${item.manifest.manifestId}`} disabled={pending || api.demoMode || item.state !== ReleaseState.STAGED || !auth.hasPermission('release:activate')} onClick={() => openConfirm('activate', item)}><Rocket size={14} />激活</Button><Button aria-label={`回滚 ${item.manifest.manifestId}`} disabled={pending || api.demoMode || item.state !== ReleaseState.ACTIVE || !auth.hasPermission('release:rollback') || !auth.hasPermission('release:read')} onClick={() => openConfirm('rollback', item)}><RotateCcw size={14} />回滚</Button><Button tone="danger" aria-label={`熔断 ${item.manifest.manifestId} 的命名空间`} disabled={pending || api.demoMode || !auth.hasPermission('release:kill-switch')} onClick={() => openConfirm('kill', item)}><Ban size={14} />熔断</Button></div></td></tr>)}</RecordTable>}
    </Panel>
    {releases.isFetchNextPageError && <StateBanner tone="error" title="后续发布清单加载失败" detail={problemDetail(releases.error)} />}
    {releases.hasNextPage && <Button disabled={pending || releases.isFetching} onClick={() => void releases.fetchNextPage()}>{releases.isFetchNextPageError ? '重试加载更多发布清单' : releases.isFetchingNextPage ? '正在加载更多发布清单' : '加载更多发布清单'}</Button>}
    <div className="release-bottom">
      <Panel><PanelHeader eyebrow="GUARDRAILS" title="发布门禁" /><ul className="guardrail-list"><li className="warning"><ShieldAlert />激活检查运行时就绪与发布时间；回滚只列出当前保留且可访问的有效版本，提交时再次校验。Kill switch 的原因随签名指令保存。</li></ul></Panel>
    </div>
    {wizardOpen && <ReleaseWizard open onClose={() => setWizardOpen(false)} onRequestActivate={(release) => { setWizardOpen(false); openConfirm('activate', release) }} />}
    {confirm && actionTarget && (
      <Modal title={confirm === 'kill' ? '启用命名空间 Kill switch' : confirm === 'rollback' ? `回滚 ${actionTarget.manifest.manifestId}` : `激活 ${actionTarget.manifest.manifestId}`} description="签名发布指令记录当前操作人，并经现有发布通道执行。请核对本次具体作用范围。" onClose={dismissConfirm}>
        <div className="danger-confirm">
          {actionError && <StateBanner tone="error" title="发布操作失败" detail={problemDetail(actionError)} />}
          <StateBanner tone={confirm === 'kill' ? 'error' : 'warn'} title="确认作用范围" detail={`${actionTarget.manifest.environment ?? ''} · ${actionTarget.manifest.cell} · ${actionTarget.manifest.runtime} · ${actionTarget.manifest.namespace}`} />
          {confirm === 'kill' && <><StateBanner tone="error" title="命名空间级熔断" detail={`作用于当前租户的整个 ${actionTarget.manifest.namespace ?? 'main'} 命名空间，不限于这条清单的环境、部署单元或运行类型。`} /><label className="field"><span>操作原因</span><input value={reason} disabled={pending} onChange={(event) => { setReason(event.target.value); setActivationKey(crypto.randomUUID()) }} placeholder="填写至少 4 个字的原因" /></label></>}
          {confirm === 'rollback' && <>
            {rollbackUncertain && <><StateBanner tone="warn" title="上次回滚结果待确认" detail={`第 ${rollback.variables?.generation} 代的回滚请求可能已完成。请用原请求重试以读取幂等回执；候选刷新失败不影响此次确认。`} /><Button disabled={!canRetryRollback} onClick={() => { if (canRetryRollback && rollback.variables) rollback.mutate(rollback.variables) }}>重试原回滚请求</Button></>}
            {candidates.isFetching && <StateBanner tone="info" title="正在查询可回滚版本" detail="仅展示服务端当前保留、曾激活且未过期的目标。" />}
            {candidates.isError && <><StateBanner tone="error" title="未能查询回滚目标" detail={problemDetail(candidates.error)} /><Button disabled={pending || candidates.isFetching} onClick={() => void candidates.refetch()}>重试回滚候选</Button></>}
            {!candidates.isFetching && !candidates.isError && candidates.data?.length === 0 && <StateBanner tone="warn" title="没有可回滚版本" detail="当前没有保留且有效、可访问的历史目标，请确认发布清单与权限范围。" />}
            <label className="field"><span>回滚目标版本</span><Select aria-label="回滚目标版本" value={targetGeneration} disabled={pending || rollbackUncertain || candidates.isFetching || candidates.isError || !candidates.data?.length} onChange={(event) => { setTargetGeneration(event.target.value); setActivationKey(crypto.randomUUID()); rollback.reset() }}><option value="" disabled>请选择服务端返回的版本</option>{(candidates.data ?? []).map((item) => <option key={item.manifestId} value={String(item.generation)}>第 {item.generation} 代 · {item.manifestId}</option>)}</Select></label>
            {chosen && <RecordTable label="回滚目标详情" columns={['清单 ID', '代际', '有效期至（北京时间）', '定义 ID', '定义版本']}>{chosen.artifacts.map((artifact) => <tr key={artifact.artifactId}><td>{chosen.manifestId}</td><td>{chosen.generation}</td><td><RecordTime value={chosen.expiresAt} /></td><td>{artifact.definitionId}</td><td>{artifact.definitionVersion}</td></tr>)}</RecordTable>}
          </>}
          <label className="confirm-check"><input type="checkbox" checked={checked} onChange={(event) => setChecked(event.target.checked)} />我已核对作用范围、流量和回滚目标</label>
          <footer className="action-bar action-bar--end modal-actions">
            <Button disabled={pending} onClick={() => closeConfirm()}>取消</Button>
            <Button tone={confirm === 'kill' ? 'danger' : 'primary'} disabled={!canExecute || activate.isPending || rollback.isPending || kill.isPending} title={api.demoMode ? '演示模式不可执行' : '确认执行'} onClick={() => {
              if (!canExecute || !actionTarget) return
              if (confirm === 'activate') activate.mutate({ manifestId: actionTarget.manifest.manifestId, idempotencyKey: activationKey })
              else if (confirm === 'rollback' && chosen) rollback.mutate({ manifestId: actionTarget.manifest.manifestId, generation: chosen.generation, idempotencyKey: activationKey })
              else if (confirm === 'kill') kill.mutate({ namespace: actionTarget.manifest.namespace ?? 'main', reason, idempotencyKey: activationKey })
            }}>确认执行</Button>
          </footer>
        </div>
      </Modal>
    )}
  </div>
}
