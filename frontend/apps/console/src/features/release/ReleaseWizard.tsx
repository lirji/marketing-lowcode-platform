import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, LoaderCircle, PackageCheck, RefreshCw } from 'lucide-react'
import { Badge, Button, Modal, StateBanner } from '../../components/ui'
import { api, ApiProblem } from '../../shared/api/client'
import { problemDetail } from '../../shared/api/problem'
import { useAuth } from '../../shared/auth/useAuth'
import type { ApprovalView, ArtifactReference, DefinitionBundle, GraphDefinition, ReleaseView } from '../../shared/api/schemas'

type RuntimeChoice = {
  runtime: 'decision' | 'journey'
  format: 'OFFER_POLICY' | 'JOURNEY_PLAN'
}

const STAGE_PROBLEM_HINTS: Record<string, string> = {
  RELEASE_NOT_APPROVED: '审核尚未完成，请回到治理中心补齐审核。',
  APPROVAL_CASE_INVALID: '所选审核单不属于这个冻结版本，请重新选择。',
  BENEFIT_RELEASE_UNBOUND: 'Offer 尚未绑定可发布的权益版本，请回权益编辑器修正。',
  BENEFIT_RELEASE_NOT_ACTIVE: '引用的权益版本不是 ACTIVE，请回权益编辑器修正。',
  BENEFIT_RELEASE_NOT_FOUND: '引用的权益版本不存在，请回权益编辑器重新绑定。',
  SKU_NOT_ACTIVE: '权益 SKU 不再是 ACTIVE，请回权益编辑器刷新并重新选择。',
  BENEFIT_SKU_TYPE_MISMATCH: '策略类型与 SKU 类型不一致，请回权益编辑器修正。',
  ARTIFACT_DEFINITION_MISMATCH: '编译产物不属于所选定义，请重新编译。',
  ARTIFACT_SOURCE_MISMATCH: '冻结图已变化，请重新读取定义并编译。',
  ARTIFACT_SIGNATURE_INVALID: '编译器签名不可信，请停止发布并检查密钥。',
}

export function releaseChoice(dialect: string): RuntimeChoice | undefined {
  if (dialect === 'OFFER_DECISION_DAG') return { runtime: 'decision', format: 'OFFER_POLICY' }
  if (dialect === 'JOURNEY_STATE_MACHINE') return { runtime: 'journey', format: 'JOURNEY_PLAN' }
  return undefined
}

function benefitReferences(graph?: GraphDefinition): string[] {
  if (!graph) return []
  return [...new Set(graph.nodes
    .filter((node) => node.stableTypeId === 'offer.fixed' || node.stableTypeId === 'offer.percentage')
    .map((node) => node.config.benefitDefinitionVersion)
    .filter((value): value is string => Boolean(value?.trim())))]
}

function problemHint(error: unknown): string {
  if (error instanceof ApiProblem && error.code && STAGE_PROBLEM_HINTS[error.code]) {
    return `${error.code}：${STAGE_PROBLEM_HINTS[error.code]}`
  }
  return problemDetail(error)
}

export function ReleaseWizard({ open, onClose, onRequestActivate }: {
  open: boolean
  onClose: () => void
  onRequestActivate: (release: ReleaseView) => void
}) {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const [caseId, setCaseId] = useState('')
  const [environment, setEnvironment] = useState('local')
  const [cell, setCell] = useState('cell-a')
  const [namespace, setNamespace] = useState('main')
  const [compileKey, setCompileKey] = useState(() => crypto.randomUUID())
  const [stageKey, setStageKey] = useState(() => crypto.randomUUID())

  const approvals = useQuery({
    queryKey: ['approvals'],
    queryFn: api.approvals,
    enabled: open && !api.demoMode && auth.hasPermission('definition:read'),
  })
  const approved = useMemo(() => (approvals.data ?? []).filter((item) => item.status === 'APPROVED'), [approvals.data])
  const selected = approved.find((item) => item.caseId === caseId)
  const definition = useQuery({
    queryKey: ['definition-version', selected?.definitionId, selected?.definitionVersion],
    queryFn: () => api.getDefinition(selected!.definitionId, selected!.definitionVersion),
    enabled: open && Boolean(selected),
  })
  const choice = definition.data ? releaseChoice(definition.data.dialect) : undefined
  const benefits = useQuery({
    queryKey: ['benefits'],
    queryFn: api.benefits,
    enabled: open && choice?.runtime === 'decision' && auth.hasPermission('benefit:read'),
  })
  const skus = useQuery({
    queryKey: ['benefit-skus', 'ACTIVE'],
    queryFn: () => api.benefitSkus('ACTIVE'),
    enabled: open && choice?.runtime === 'decision' && auth.hasPermission('benefit:read'),
  })
  const releases = useQuery({
    queryKey: ['releases'],
    queryFn: api.releases,
    enabled: open && auth.hasPermission('release:read'),
    refetchInterval: (query) => query.state.data?.some((item) => item.state === 'STAGED') ? 2_000 : false,
  })

  const compile = useMutation({
    mutationFn: async (bundle: DefinitionBundle) => {
      const runtimeChoice = releaseChoice(bundle.dialect)
      if (!runtimeChoice || !bundle.graph) throw new Error('所选定义缺少可编译的冻结图或不支持该 dialect。')
      const report = await api.compile({
        definitionId: bundle.definitionId,
        definitionVersion: bundle.version,
        format: runtimeChoice.format,
        namespace,
        modelName: bundle.definitionId,
        source: null,
        graph: bundle.graph,
      }, compileKey)
      if (!report.valid || !report.artifactId) return { report, artifact: undefined }
      const artifact = await api.getArtifact(report.artifactId)
      return { report, artifact }
    },
  })

  const references = benefitReferences(definition.data?.graph)
  const activeSkuIds = new Set((skus.data ?? []).map((item) => item.skuId))
  const benefitsByReference = new Map((benefits.data ?? []).map((item) => [`${item.benefitId}@${item.version}`, item]))
  const benefitReady = choice?.runtime !== 'decision' || (references.length > 0 && references.every((reference) => {
    const benefit = benefitsByReference.get(reference)
    return benefit?.status === 'ACTIVE' && Boolean(benefit.benefitSkuId && activeSkuIds.has(benefit.benefitSkuId))
  }))
  const preflightPending = choice?.runtime === 'decision' && (benefits.isLoading || skus.isLoading)
  const stageBlocker = !selected ? '请选择已批准定义'
    : !choice ? '该定义不是 decision 或 journey runtime 支持的 dialect'
      : choice.runtime === 'decision' && !auth.hasPermission('benefit:read') ? '缺少 benefit:read，无法核对 ACTIVE SKU'
        : preflightPending ? '正在核对权益绑定'
          : !benefitReady ? '权益未绑定同租户 ACTIVE SKU，不能暂存'
            : !compile.data?.report.valid || !compile.data.artifact ? '请先完成有效编译并读取制品'
              : !auth.hasPermission('release:write') ? '缺少 release:write'
                : ''

  const stage = useMutation({
    mutationFn: async () => {
      if (!selected || !definition.data || !choice || !compile.data?.artifact) throw new Error('暂存前置条件不完整')
      const artifact = compile.data.artifact
      const reference: ArtifactReference = {
        artifactId: artifact.artifactId,
        type: artifact.type,
        uri: `compiler://${artifact.artifactId}`,
        checksum: artifact.checksum,
        sourceDigest: artifact.sourceDigest,
        signatureKeyId: artifact.signatureKeyId,
        signature: artifact.signature,
        abi: artifact.abi,
        definitionId: artifact.definitionId,
        definitionVersion: artifact.definitionVersion,
      }
      const staged = await api.stageRelease({
        definitionId: definition.data.definitionId,
        definitionVersion: definition.data.version,
        environment,
        cell,
        runtime: choice.runtime,
        namespace,
        artifacts: [reference],
        schemaVersions: { terms: 'terms-v1' },
        canaryBasisPoints: 0,
        activationAt: new Date(Date.now() - 1_000).toISOString(),
        approvalCaseIds: [selected.caseId],
      }, stageKey)
      await queryClient.invalidateQueries({ queryKey: ['releases'] })
      return staged
    },
  })

  const stagedRelease = stage.data
    ? (releases.data ?? []).find((item) => item.manifest.manifestId === stage.data?.manifest.manifestId) ?? stage.data
    : undefined
  const ready = Boolean(stagedRelease?.state === 'STAGED' && stagedRelease.readyReplicas > 0 && stagedRelease.readyCapacity > 0)
  const missingPermissions = ['definition:read', 'definition:compile', 'artifact:read', 'release:write', 'release:read', 'release:activate']
    .filter((permission) => !auth.hasPermission(permission))

  const selectApproval = (value: string) => {
    setCaseId(value)
    compile.reset()
    stage.reset()
    setCompileKey(crypto.randomUUID())
    setStageKey(crypto.randomUUID())
  }

  const changeStageScope = (field: 'environment' | 'cell', value: string) => {
    if (field === 'environment') setEnvironment(value)
    else setCell(value)
    stage.reset()
    setStageKey(crypto.randomUUID())
  }

  const changeNamespace = (value: string) => {
    setNamespace(value)
    // namespace 同时属于编译与暂存请求，修改后必须开启两条新幂等命令并重新读取制品。
    compile.reset()
    stage.reset()
    setCompileKey(crypto.randomUUID())
    setStageKey(crypto.randomUUID())
  }

  if (!open) return null
  return <Modal title="编译并暂存" description="一次只发布一个 decision 或 journey runtime；ACK 只能由 runtime 发送。" onClose={onClose}>
    <div className="release-wizard" aria-label="发布向导">
      {api.demoMode && <StateBanner tone="warn" title="演示模式不编译" detail="关闭 DEMO_MODE 后才能调用真实编译与发布 API。" />}
      {missingPermissions.length > 0 && <StateBanner tone="warn" title="权限不足" detail={`缺少 ${missingPermissions.join('、')}`} />}

      <section>
        <header><Badge tone={selected ? 'good' : 'warn'}>1</Badge><strong>选择已批准定义</strong></header>
        {approvals.isError && <StateBanner tone="error" title="审核单加载失败" detail={problemDetail(approvals.error)} />}
        {definition.isError && <StateBanner tone="error" title="冻结定义加载失败" detail={problemDetail(definition.error)} />}
        <label className="field"><span>批准单 / 冻结版本</span><select aria-label="批准单 / 冻结版本" value={caseId} onChange={(event) => selectApproval(event.target.value)} disabled={api.demoMode || !auth.hasPermission('definition:read')}>
          <option value="">请选择</option>
          {approved.map((item: ApprovalView) => <option value={item.caseId} key={item.caseId}>{item.definitionId} · v{item.definitionVersion} · {item.caseId}</option>)}
        </select></label>
        {definition.data && <small>{definition.data.dialect} · {choice?.runtime ?? '不支持'} runtime</small>}
      </section>

      <section>
        <header><Badge tone={compile.data?.report.valid ? 'good' : 'warn'}>2</Badge><strong>编译并读取制品</strong></header>
        <Button onClick={() => definition.data && compile.mutate(definition.data)} disabled={!definition.data || api.demoMode || !auth.hasPermission('definition:compile') || !auth.hasPermission('artifact:read') || compile.isPending} title={!auth.hasPermission('definition:compile') ? '缺少 definition:compile' : !auth.hasPermission('artifact:read') ? '缺少 artifact:read' : '编译冻结图'}>
          {compile.isPending ? <LoaderCircle size={15} /> : <PackageCheck size={15} />}编译
        </Button>
        {compile.isError && <StateBanner tone="error" title="编译失败" detail={problemDetail(compile.error)} />}
        {compile.data && !compile.data.report.valid && <StateBanner tone="error" title="编译未通过，已停止暂存" detail={compile.data.report.messages.join('；') || '编译器返回 valid=false'} />}
        {compile.data?.artifact && <StateBanner tone="success" title="制品已读取" detail={`${compile.data.artifact.artifactId} · ${compile.data.artifact.abi}`} />}
      </section>

      <section>
        <header><Badge tone={stage.data ? 'good' : 'warn'}>3</Badge><strong>暂存</strong></header>
        <div className="release-wizard-fields">
          <label className="field"><span>Environment</span><input value={environment} onChange={(event) => changeStageScope('environment', event.target.value)} /></label>
          <label className="field"><span>Cell</span><input value={cell} onChange={(event) => changeStageScope('cell', event.target.value)} /></label>
          <label className="field"><span>Runtime</span><input value={choice?.runtime ?? ''} readOnly /></label>
          <label className="field"><span>Namespace</span><input value={namespace} onChange={(event) => changeNamespace(event.target.value)} /></label>
        </div>
        {stageBlocker && <small className="release-blocker">{stageBlocker}</small>}
        <Button tone="primary" onClick={() => stage.mutate()} disabled={Boolean(stageBlocker) || !environment.trim() || !cell.trim() || !namespace.trim() || stage.isPending} title={stageBlocker || '暂存已批准制品'}>暂存</Button>
        {stage.isError && <StateBanner tone="error" title="暂存失败" detail={problemHint(stage.error)} />}
        {stage.data && <StateBanner tone="success" title="已暂存" detail={`${stage.data.manifest.manifestId} · ${stage.data.state}`} />}
      </section>

      <section>
        <header><Badge tone={ready ? 'good' : 'warn'}>4</Badge><strong>等待 runtime ACK</strong></header>
        <p>控制台只轮询 GET /releases；runtime 预热并验签后自行回 ACK，控制台不代签。</p>
        <Button onClick={() => void releases.refetch()} disabled={!stagedRelease || releases.isFetching}><RefreshCw size={15} />刷新状态</Button>
        {stagedRelease && <div className="release-ready"><CheckCircle2 size={16} /><span>{stagedRelease.state} · readyReplicas {stagedRelease.readyReplicas} · capacity {stagedRelease.readyCapacity}</span></div>}
      </section>

      <footer className="modal-actions">
        <Button onClick={onClose}>关闭</Button>
        <Button tone="primary" disabled={!ready || !auth.hasPermission('release:activate')} title={!auth.hasPermission('release:activate') ? '缺少 release:activate' : ready ? '进入激活确认' : 'RUNTIME_NOT_READY：继续等待 ACK'} onClick={() => stagedRelease && onRequestActivate(stagedRelease)}>
          进入激活确认
        </Button>
      </footer>
    </div>
  </Modal>
}
