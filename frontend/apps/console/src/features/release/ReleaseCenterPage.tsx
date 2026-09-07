import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Ban, CheckCircle2, PackageCheck, RotateCcw, Rocket, ShieldAlert } from 'lucide-react'
import { Badge, Button, DemoBanner, EmptyState, Modal, PageHeader, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { problemDetail } from '../../shared/api/problem'
import { api } from '../../shared/api/client'
import { useAuth } from '../../shared/auth/useAuth'
import type { ReleaseView } from '../../shared/api/schemas'
import { ReleaseWizard } from './ReleaseWizard'

type ConfirmKind = 'activate' | 'rollback' | 'kill'

export function ReleaseCenterPage() {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const [confirm, setConfirm] = useState<ConfirmKind | null>(null)
  const [reason, setReason] = useState('')
  const [checked, setChecked] = useState(false)
  const [result, setResult] = useState('')
  const [wizardOpen, setWizardOpen] = useState(false)
  const [actionTarget, setActionTarget] = useState<ReleaseView>()
  const [activationKey, setActivationKey] = useState(() => crypto.randomUUID())
  const releases = useQuery({
    queryKey: ['releases'],
    queryFn: api.demoMode ? async (): Promise<ReleaseView[]> => [] : api.releases,
  })
  const latest = releases.data?.[0]
  const activate = useMutation({
    mutationFn: ({ manifestId, idempotencyKey }: { manifestId: string; idempotencyKey: string }) => api.activateRelease(manifestId, idempotencyKey),
    onSuccess: (value) => { setResult(`灰度/激活已提交 ${value.manifest.manifestId} · ${value.state}`); closeConfirm() },
  })
  const rollback = useMutation({
    mutationFn: ({ manifestId, generation }: { manifestId: string; generation: number }) => api.rollbackRelease(manifestId, generation),
    onSuccess: (value) => { setResult(`回滚已提交 ${value.manifest.manifestId}`); closeConfirm() },
  })
  const kill = useMutation({
    mutationFn: ({ namespace, reason }: { namespace: string; reason: string }) => api.setKillSwitch(namespace, true, reason),
    onSuccess: (value) => { setResult(`Kill switch 已启用 ${value.namespace} · seq ${value.sequence ?? 0}`); closeConfirm() },
  })
  const closeConfirm = () => { setConfirm(null); setActionTarget(undefined); setReason(''); setChecked(false); void queryClient.invalidateQueries({ queryKey: ['releases'] }) }
  const openConfirm = (kind: ConfirmKind, target: ReleaseView) => {
    setActionTarget(target)
    setActivationKey(crypto.randomUUID())
    setConfirm(kind)
  }
  const actionError = activate.error ?? rollback.error ?? kill.error
  const canExecute = Boolean(actionTarget) && checked && reason.trim().length >= 4 && !api.demoMode
  const wizardMissingPermissions = ['definition:read', 'definition:compile', 'artifact:read', 'release:write', 'release:read']
    .filter((permission) => !auth.hasPermission(permission))
  const canOpenWizard = wizardMissingPermissions.length === 0

  return <div className="workspace release-page">
    <PageHeader eyebrow={latest ? `RELEASE · ${latest.manifest.manifestId}` : 'RELEASE CENTER'} title={latest ? `${latest.manifest.cell} / ${latest.manifest.namespace ?? 'ns'}` : '发布中心'} description="签名 Manifest 是 desired state；所有 Cell 验签、预热并 ACK 后，流量才会进入新代际。" actions={<>
      <Button onClick={() => setWizardOpen(true)} disabled={api.demoMode || !canOpenWizard} title={api.demoMode ? '演示模式不编译' : canOpenWizard ? '选择批准版本并编译暂存' : `缺少 ${wizardMissingPermissions.join('、')}`}><PackageCheck size={15} />编译并暂存</Button>
      <Button onClick={() => latest && openConfirm('rollback', latest)} disabled={!latest || !auth.hasPermission('release:rollback')} title={auth.hasPermission('release:rollback') ? '回滚' : '缺少 release:rollback'}><RotateCcw size={15} />回滚</Button>
      <Button tone="danger" onClick={() => latest && openConfirm('kill', latest)} disabled={!latest || !auth.hasPermission('release:kill-switch')} title={auth.hasPermission('release:kill-switch') ? 'Kill switch' : '缺少 release:kill-switch'}><Ban size={15} />Kill switch</Button>
      <Button tone="primary" onClick={() => latest && openConfirm('activate', latest)} disabled={!latest || !auth.hasPermission('release:activate')} title={auth.hasPermission('release:activate') ? '推进灰度' : '缺少 release:activate'}><Rocket size={15} />推进灰度</Button>
    </>} />
    <DemoBanner />
    {result && <StateBanner tone="success" title="发布操作已登记" detail={result} />}
    {releases.isError && <StateBanner tone="error" title="发布列表加载失败" detail={problemDetail(releases.error)} />}
    {actionError && <StateBanner tone="error" title="发布操作失败" detail={problemDetail(actionError)} />}
    {!api.demoMode && (releases.data ?? []).length === 0 && !releases.isLoading ? <EmptyState title="还没有 Release Manifest" detail="通过已批准定义编译并 stage 后，才会出现在此矩阵。" action={<Button tone="primary" onClick={() => setWizardOpen(true)} disabled={!canOpenWizard} title={canOpenWizard ? '选择批准版本并编译暂存' : `缺少 ${wizardMissingPermissions.join('、')}`}><PackageCheck size={15} />编译并暂存</Button>} /> : null}
    {latest && <div className="release-summary"><Panel><p>Manifest</p><strong>{latest.manifest.manifestId.slice(0, 12)}</strong><small>{latest.state}</small></Panel><Panel><p>规则制品</p><strong>{latest.manifest.artifacts.length}</strong><small>checksum verified</small></Panel><Panel><p>就绪副本</p><strong>{latest.readyReplicas}</strong><small>容量 {latest.readyCapacity}</small></Panel><Panel><p>当前灰度</p><strong>{((latest.manifest.canaryBasisPoints ?? 0) / 100).toFixed(0)}%</strong><small>canary basis points</small></Panel></div>}
    <Panel>
      <PanelHeader eyebrow="CELL READINESS" title="运行时就绪矩阵" />
      {api.demoMode ? <EmptyState title="演示模式不展示真实发布槽" detail="关闭 DEMO_MODE 后读取 /api/v1/releases。" /> : <div className="release-matrix"><div className="matrix-head"><span>Cell</span><span>状态</span><span>Generation</span><span>副本 ACK</span><span>声明容量</span><span>验签</span><span>流量</span></div>{(releases.data ?? []).map((item) => <div className="matrix-row" key={item.manifest.manifestId}><strong>{item.manifest.cell}</strong><Badge tone={item.state === 'ACTIVE' ? 'good' : 'warn'}>{item.state}</Badge><code>#{item.manifest.generation}</code><span>{item.readyReplicas}</span><span>{item.readyCapacity}</span><span className="verified"><CheckCircle2 size={14} />{item.manifest.signature ? 'signed' : 'unsigned'}</span><div className="traffic"><i><b style={{ width: `${(item.manifest.canaryBasisPoints ?? 0) / 100}%` }} /></i><strong>{((item.manifest.canaryBasisPoints ?? 0) / 100).toFixed(0)}%</strong></div></div>)}</div>}
    </Panel>
    <div className="release-bottom">
      <Panel><PanelHeader eyebrow="GUARDRAILS" title="发布门禁" /><ul className="guardrail-list"><li className="warning"><ShieldAlert />推进、回滚和 Kill switch 必须填写审批依据，并在确认框勾选范围。</li></ul></Panel>
    </div>
    {wizardOpen && <ReleaseWizard open onClose={() => setWizardOpen(false)} onRequestActivate={(release) => { setWizardOpen(false); openConfirm('activate', release) }} />}
    {confirm && actionTarget && (
      <Modal title={confirm === 'kill' ? '启用全局 Kill switch' : confirm === 'rollback' ? `回滚 ${actionTarget.manifest.manifestId}` : `激活 ${actionTarget.manifest.manifestId}`} description="此操作将写入不可篡改审计，并由 Release Coordinator 执行。" onClose={() => closeConfirm()}>
        <div className="danger-confirm">
          <StateBanner tone={confirm === 'kill' ? 'error' : 'warn'} title="确认作用范围" detail={`${actionTarget.manifest.environment ?? ''} · ${actionTarget.manifest.cell} · ${actionTarget.manifest.runtime} · ${actionTarget.manifest.namespace}`} />
          <label className="field"><span>审批号与操作原因</span><input value={reason} onChange={(event) => setReason(event.target.value)} placeholder="APR-7201 / 原因" /></label>
          <label className="confirm-check"><input type="checkbox" checked={checked} onChange={(event) => setChecked(event.target.checked)} />我已核对作用范围、流量和回滚目标</label>
          <footer className="modal-actions">
            <Button onClick={() => closeConfirm()}>取消</Button>
            <Button tone={confirm === 'kill' ? 'danger' : 'primary'} disabled={!canExecute || activate.isPending || rollback.isPending || kill.isPending} title={api.demoMode ? '演示模式不可执行' : '确认执行'} onClick={() => {
              if (!canExecute || !actionTarget) return
              if (confirm === 'activate') activate.mutate({ manifestId: actionTarget.manifest.manifestId, idempotencyKey: activationKey })
              else if (confirm === 'rollback') rollback.mutate({ manifestId: actionTarget.manifest.manifestId, generation: Math.max(1, (actionTarget.manifest.stableGeneration ?? actionTarget.manifest.generation) - 1 || 1) })
              else kill.mutate({ namespace: actionTarget.manifest.namespace ?? 'main', reason })
            }}>确认执行</Button>
          </footer>
        </div>
      </Modal>
    )}
  </div>
}
