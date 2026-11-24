import { useCallback, useEffect, useRef, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { api } from '../../shared/api/client'
import { OperationState, type BundleAction, type BundleOperation, type BundleOperationInput, type ReleaseBundle } from '../../shared/api/releaseBundles'
import { problemDetail } from '../../shared/api/problem'
import { useAuth } from '../../shared/auth/useAuth'
import { Badge, Button, Modal, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { RecordTime } from '../../components/RecordTable'
import { uncertainWrite } from './bundleErrors'
import { BundleSchedules } from './BundleSchedules'

const actions = { ACTIVATE: '整体生效', ROLLBACK: '整体回滚' }
const permission = { ACTIVATE: 'release:activate', ROLLBACK: 'release:rollback' }
const statuses = { PENDING: '等待运行回执', APPLIED: '已应用', EXPIRED: '已过期', REJECTED: '已拒绝' }
type Command = { input: BundleOperationInput; key: string }
const pollingMs = 5000
const probePending = 'RELEASE_BUNDLE_PROBE_PENDING'
const recoveryDelays = [3000, 5000, 8000, 13000, 21000, 30000]
const reasonLabels: Record<string, string> = {
  [probePending]: '等待 Journey 作业提交所有子任务的执行回执',
  RELEASE_BUNDLE_OPERATION_NOT_READY: '成员就绪副本或容量不足',
  RELEASE_BUNDLE_OPERATION_KILLED: '组合成员已紧急停用',
  RELEASE_BUNDLE_RUNTIME_UNAVAILABLE: '运行端暂时无法核实原命令',
  RELEASE_BUNDLE_RUNTIME_CONFIG: '运行部署连接配置不全',
  RELEASE_BUNDLE_RUNTIME_EVIDENCE: '运行证据与本次授权不匹配',
}

export function BundleControls({ bundle, scope, ready }: { bundle: ReleaseBundle; scope: string; ready: boolean }) {
  const auth = useAuth()
  const [confirm, setConfirm] = useState<Command>()
  const [trackedId, setTrackedId] = useState('')
  const attempts = useRef(0)
  const operationHeading = useRef<HTMLDivElement>(null)
  const summary = useQuery({ queryKey: ['bundle-operations', scope, bundle.campaignId, bundle.environment, bundle.cell, bundle.version],
    queryFn: () => api.bundleOperations(bundle), retry: false,
    refetchInterval: query => query.state.data?.latestOperation?.state === OperationState.PENDING ? pollingMs : false,
  })
  const latest = summary.data?.latestOperation
  const tracked = useQuery({ queryKey: ['bundle-operation', scope, bundle.campaignId, trackedId], enabled: !!trackedId,
    queryFn: () => api.bundleOperation(bundle.campaignId, trackedId), retry: false,
    refetchInterval: query => query.state.data?.state === OperationState.PENDING ? pollingMs : false,
  })
  const operation = trackedId ? (tracked.isError ? undefined : tracked.data) : (summary.isError ? undefined : latest)
  const readSummary = summary.refetch, readOperation = tracked.refetch
  const refresh = useCallback(async () => { await Promise.all([readSummary(), ...(trackedId ? [readOperation()] : [])]) }, [readSummary, readOperation, trackedId])
  const write = useMutation({ mutationFn: (command: Command) => api.startBundleOperation(bundle.campaignId, command.input, command.key),
    onSuccess: async result => { attempts.current = 0; setConfirm(undefined); setTrackedId(result.operationId); await summary.refetch() },
    onError: async () => { await summary.refetch() },
  })
  const retry = useMutation({ mutationFn: (id: string) => api.bundleOperation(bundle.campaignId, id, true), onSettled: refresh })
  const unknown = write.isError && uncertainWrite(write.error)
  const pending = write.isPending || retry.isPending
  const pendingRef = useRef(pending)
  useEffect(() => { pendingRef.current = pending }, [pending])
  const close = useCallback(() => { if (!pendingRef.current) setConfirm(undefined) }, [])
  // 只对本页明确提交且已登记的同一操作继续取证，切换版本或身份时释放计时器。
  const resumeId = trackedId && operation?.operationId === write.data?.operationId && operation?.state === OperationState.PENDING
    && operation.reasonCode === probePending && auth.hasPermission(permission[operation.action]) ? trackedId : undefined
  const resume = retry.mutate
  const retryFailed = retry.isError
  useEffect(() => {
    if (!resumeId || pending || retryFailed || attempts.current >= recoveryDelays.length) return
    const timer = window.setTimeout(() => { attempts.current++; resume(resumeId) }, recoveryDelays[attempts.current])
    return () => window.clearTimeout(timer)
  }, [resumeId, resume, pending, retryFailed])
  const available = !summary.isError && !!summary.data && summary.data.appliedVersion !== bundle.version && latest?.state !== OperationState.PENDING
  const forward = summary.data?.appliedVersion === null || (summary.data?.appliedVersion ?? Infinity) < bundle.version
  const allowed = (action: BundleAction) => available && auth.hasPermission(permission[action])
    && (action === 'ROLLBACK' ? summary.data?.rollbackEligible : forward && ready)
  const open = (action: BundleAction) => {
    if (!allowed(action) || pending || unknown) return
    write.reset(); retry.reset()
    setConfirm({ input: { action, version: bundle.version, expectedAppliedSequence: summary.data!.appliedSequence }, key: crypto.randomUUID() })
  }
  return <>
    <Panel><div tabIndex={-1} ref={operationHeading} aria-label="整体生效与回滚"><PanelHeader title="整体生效与回滚" aside={<Button disabled={summary.isFetching || pending} onClick={() => void refresh()}>查询运行结果</Button>} /></div>
      {summary.isLoading && <p role="status">正在核对当前生效版本…</p>}
      {summary.isError && <StateBanner tone="error" title="生效位置查询失败" detail={problemDetail(summary.error)} />}
      {summary.isSuccess && <StateBanner tone={summary.data.appliedVersion === null ? 'info' : 'success'}
        title={summary.data.appliedVersion === null ? '此位置尚无生效组合' : `当前生效版本 v${summary.data.appliedVersion}`}
        detail={`${bundle.environment} / ${bundle.cell}，已应用序号 ${summary.data.appliedSequence}。新参与使用该位置的生效版本，已有参与保留原版本。`} />}
      {write.isError && !confirm && <StateBanner tone={unknown ? 'warn' : 'error'} title={unknown ? '原发布请求结果待确认' : '发布命令未完成'} detail={problemDetail(write.error)} />}
      {unknown && write.variables && !confirm && <Button disabled={pending || !auth.hasPermission(permission[write.variables.input.action])} onClick={() => write.mutate(write.variables!)}>用原请求查询发布回执</Button>}
      <div className="action-bar bundle-actions">
        <Button tone="primary" disabled={!allowed('ACTIVATE') || pending || unknown} onClick={() => open('ACTIVATE')}>整体生效 v{bundle.version}</Button>
        <Button disabled={!allowed('ROLLBACK') || pending || unknown} onClick={() => open('ROLLBACK')}>整体回滚至 v{bundle.version}</Button>
      </div>
      <p className="bundle-note">准备运行端后可发起发布。回滚目标须曾在同一位置生效，并在提交时通过复核。</p>
      {tracked.isError && <StateBanner tone="error" title="原操作查询失败" detail={problemDetail(tracked.error)} />}
      {operation && <div className="bundle-result">
        <h3>{trackedId ? '当前跟踪的发布操作' : '此位置最近的发布操作'}</h3>
        <OperationDetails operation={operation} />
        <div className="action-bar bundle-actions">
          {operation.state === OperationState.PENDING && <Button disabled={pending || !auth.hasPermission(permission[operation.action])} onClick={() => retry.mutate(operation.operationId)}>继续原操作</Button>}
          {trackedId && latest?.operationId && latest.operationId !== trackedId && <Button disabled={pending} onClick={() => { setTrackedId(''); retry.reset() }}>查看最近操作</Button>}
        </div>
      </div>}
      {retry.isError && <StateBanner tone="error" title="原操作仍待核对" detail={problemDetail(retry.error)} />}
    </Panel>
    <BundleSchedules bundle={bundle} scope={scope} appliedSequence={summary.isSuccess ? summary.data.appliedSequence : undefined}
      canAdvance={forward} onRefresh={refresh} onTrack={id => { setTrackedId(id); operationHeading.current?.focus() }} />
    {confirm && <Modal title={`确认${actions[confirm.input.action]} v${bundle.version}`} onClose={close}>
      <div className="danger-confirm"><dl className="bundle-facts"><div><dt>活动 ID</dt><dd>{bundle.campaignId}</dd></div><div><dt>环境</dt><dd>{bundle.environment}</dd></div>
        <div><dt>部署单元</dt><dd>{bundle.cell}</dd></div><div><dt>目标组合</dt><dd>v{bundle.version} · {bundle.bundleId}</dd></div></dl>
        <p>本次命令以已应用序号 {confirm.input.expectedAppliedSequence} 为前提。全体运行成员确认后，新参与将使用目标组合。</p>
        {write.isError && <StateBanner tone={unknown ? 'warn' : 'error'} title={unknown ? '提交结果待确认' : '发布请求失败'} detail={problemDetail(write.error)} />}
        <footer className="action-bar action-bar--end modal-actions"><Button disabled={pending} onClick={close}>关闭</Button>
          {unknown ? <Button disabled={pending || !auth.hasPermission(permission[confirm.input.action])} onClick={() => write.mutate(confirm)}>用原请求查询发布回执</Button> :
            <Button tone="primary" disabled={pending || !allowed(confirm.input.action) || confirm.input.expectedAppliedSequence !== summary.data?.appliedSequence} onClick={() => write.mutate(confirm)}>确认{actions[confirm.input.action]}</Button>}
        </footer>
      </div>
    </Modal>}
  </>
}

function OperationDetails({ operation }: { operation: BundleOperation }) {
  return <dl className="bundle-facts">
    <div><dt>操作状态</dt><dd><Badge tone={operation.state === OperationState.APPLIED ? 'good' : operation.state === OperationState.PENDING ? 'warn' : 'danger'}>{statuses[operation.state]}</Badge></dd></div>
    <div><dt>操作 ID</dt><dd>{operation.operationId}</dd></div><div><dt>操作</dt><dd>{actions[operation.action]} v{operation.version}</dd></div>
    <div><dt>激活序号</dt><dd>{operation.activationSequence}</dd></div><div><dt>更新时间（北京）</dt><dd><RecordTime value={operation.updatedAt} /></dd></div>
    {operation.reasonCode && <div><dt>处理情况</dt><dd>{reasonLabels[operation.reasonCode] ?? operation.reasonCode}</dd></div>}
  </dl>
}
