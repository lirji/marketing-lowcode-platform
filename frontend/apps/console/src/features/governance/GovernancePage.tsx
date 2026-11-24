import { RecordTable, RecordTime } from '../../components/RecordTable'
import { useCallback, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Check, Eye, ShieldAlert } from 'lucide-react'
import { Badge, Button, DemoBanner, EmptyState, Modal, PageHeader, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { problemDetail } from '../../shared/api/problem'
import { api } from '../../shared/api/client'
import { useAuth } from '../../shared/auth/useAuth'
import { Select } from '../../components/Select'
import { FieldHint } from '../../components/FieldHint'
import { approvalScopeKey, APPROVAL_STATUS, useApprovalPages } from '../../shared/api/useApprovalPages'
import type { ApprovalRole, ApprovalStatus, ApprovalView } from '../../shared/api/schemas'

const ROLE_LABEL: Record<ApprovalRole, string> = { BUSINESS: '业务', FINANCE: '资金', COMPLIANCE: '合规', MERCHANT: '商家' }

export function GovernancePage() {
  const auth = useAuth()
  return <GovernanceQueue key={approvalScopeKey(auth)} />
}

function GovernanceQueue() {
  const auth = useAuth()
  const [status, setStatus] = useState<ApprovalStatus | ''>(APPROVAL_STATUS.OPEN)
  const queryClient = useQueryClient()
  const [selected, setSelected] = useState<ApprovalView | null>(null)
  const [comment, setComment] = useState('')
  const [notice, setNotice] = useState('')
  const approvals = useApprovalPages(status || undefined)
  const decide = useMutation({
    mutationFn: async ({ caseId, role, decision }: { caseId: string; role: ApprovalRole; decision: 'APPROVE' | 'REJECT' }) =>
      api.decideApproval(caseId, role, decision, comment.trim().slice(0, 128)),
    onSuccess: (value) => {
      setNotice(`${value.caseId} 已记录 ${value.status}，审计哈希链已追加。`)
      setSelected(null)
      setComment('')
      void queryClient.invalidateQueries({ queryKey: ['approvals'] })
    },
  })
  const closeDetail = useCallback(() => { if (!decide.isPending) setSelected(null) }, [decide.isPending])
  const items = approvals.items
  const roleFor = (item: ApprovalView | null) => {
    if (!item || item.status !== APPROVAL_STATUS.OPEN || Object.values(item.approvals).includes(auth.subject)) return undefined
    const roles = item.requiredRoles.length
      ? item.requiredRoles
      : (['BUSINESS', 'FINANCE', 'COMPLIANCE', 'MERCHANT'] as ApprovalRole[])
    return roles.find((role) => !item.approvals[role] && auth.hasPermission(`approval:${role.toLowerCase()}`))
  }
  const availableRole = roleFor(selected)

  return <div className="workspace governance-page">
    <PageHeader eyebrow="治理与审核" title="审核与风险" description="提交者不能审批自己的变更；业务、资金、风控和合规按风险等级形成法定人数。" actions={<Button disabled title="风险策略尚未接入控制面"><ShieldAlert size={15} />风险策略</Button>} />
    <DemoBanner />
    {notice && <StateBanner tone="success" title="审核决定已记录" detail={notice} />}
    {!api.demoMode && !approvals.canRead && <StateBanner tone="warn" title="没有查看审核单的权限" detail="需要定义读取或审核角色权限。" />}
    {approvals.isError && <StateBanner tone="error" title={approvals.isFetchNextPageError ? '后续审核单加载失败' : '审核队列加载失败'} detail={problemDetail(approvals.error)} />}
    <div className="action-bar action-bar--fields toolbar">
      <label className="field"><span>审核状态</span><Select aria-label="审核状态" aria-describedby="approval-status-hint" value={status} disabled={api.demoMode || !approvals.canRead || decide.isPending} onChange={(event) => { setStatus(event.target.value as ApprovalStatus | ''); setSelected(null); setNotice('') }}><option value="OPEN">待审核</option><option value="APPROVED">已批准</option><option value="REJECTED">已驳回</option><option value="">全部状态</option></Select><FieldHint id="approval-status-hint">按当前组织和门店范围在服务端筛选，按提交时间由新到旧加载。</FieldHint></label>
      <Button disabled={api.demoMode || !approvals.canRead || approvals.isFetching || decide.isPending} onClick={() => { setSelected(null); void approvals.refresh() }}>刷新审核队列</Button>
    </div>
    <div className="governance-layout">
      <Panel>
        <PanelHeader eyebrow="VISIBLE QUEUE" title="审核队列" aside={<Badge tone="warn">已加载 {items.length} 项{approvals.hasNextPage ? '，还有更多' : ''}</Badge>} />
        {api.demoMode ? <EmptyState title="演示模式不展示真实审核单" detail="关闭 DEMO_MODE 后读取真实审批分页。" /> : !approvals.canRead ? null : approvals.isPending ? <StateBanner tone="loading" title="正在加载审核单" detail="正在读取当前权限范围内的记录。" /> : items.length === 0 ? (approvals.isError ? null : <EmptyState title="当前筛选没有审核单" detail="可切换审核状态或刷新队列核对新提交。" />) : <RecordTable label={status === APPROVAL_STATUS.OPEN ? '待审核变更' : '审核记录'} columns={['审核单 ID', '定义 ID', '定义版本', '状态', '提交人', '所需角色', '已批准人数', '所需人数', '更新时间（北京时间）', '操作']} actions>{items.map((item) => <tr key={item.caseId}><td>{item.caseId}</td><td>{item.definitionId}</td><td>{item.definitionVersion}</td><td><Badge tone={item.status === APPROVAL_STATUS.APPROVED ? 'good' : item.status === APPROVAL_STATUS.REJECTED ? 'danger' : 'warn'}>{item.status}</Badge></td><td>{item.submittedBy}</td><td>{item.requiredRoles.map((role) => ROLE_LABEL[role]).join('、')}</td><td>{Object.keys(item.approvals).length}</td><td>{item.requiredRoles.length}</td><td><RecordTime value={item.updatedAt} /></td><td><Button aria-label={`查看审核：${item.caseId}`} onClick={() => { setSelected(item); setComment(''); decide.reset() }}><Eye size={15} />查看审核</Button></td></tr>)}</RecordTable>}
        {approvals.hasNextPage && <div className="action-bar toolbar"><Button disabled={approvals.isFetching || decide.isPending} onClick={() => void approvals.fetchNextPage()}>{approvals.isFetchingNextPage ? '正在加载…' : approvals.isFetchNextPageError ? '重试加载更多审核单' : '加载更多审核单'}</Button></div>}
        {!api.demoMode && approvals.canRead && !approvals.isPending && !approvals.isError && <p className="muted">{approvals.hasNextPage ? '数量仅代表已加载记录。' : '本次查询已加载到底。'}审核状态可能变化，操作前可刷新核对。</p>}
      </Panel>
      <aside>
        <Panel>
          <PanelHeader eyebrow="SEPARATION OF DUTIES" title="职责分离" />
          <RecordTable label="职责分离" columns={['角色', '当前用户', '审核权限']}>
            {(['BUSINESS', 'FINANCE', 'COMPLIANCE', 'MERCHANT'] as ApprovalRole[]).map((role) => {
              const allowed = auth.hasPermission(`approval:${role.toLowerCase()}`)
              return <tr key={role}><td>{ROLE_LABEL[role]}负责人</td><td>{auth.displayName}</td><td><Badge tone={allowed ? 'good' : 'danger'}>{allowed ? '可审核' : '不可审批'}</Badge></td></tr>
            })}
          </RecordTable>
        </Panel>
        <Panel>
          <PanelHeader eyebrow="QUEUE" title="已加载记录分布" />
          <dl className="summary-list">
            <div><dt>待审</dt><dd>{items.filter((item) => item.status === APPROVAL_STATUS.OPEN).length}</dd></div>
            <div><dt>已批准</dt><dd>{items.filter((item) => item.status === APPROVAL_STATUS.APPROVED).length}</dd></div>
            <div><dt>已驳回</dt><dd>{items.filter((item) => item.status === APPROVAL_STATUS.REJECTED).length}</dd></div>
          </dl>
        </Panel>
      </aside>
    </div>
    {selected && (
      <Modal title="审核变更详情" description="核对变更与审核依据" onClose={closeDetail}>
        <div className="approval-detail">
          {decide.isError && <StateBanner tone="error" title="审核失败" detail={problemDetail(decide.error)} />}
          {decide.isPending && <StateBanner tone="loading" title="正在记录审核决定，请等待结果" detail="关闭页面不会撤销已经提交的决定。" />}
          <StateBanner tone="warn" title="批准后不会立即生效" detail="仍需编译、签名、运行时 ACK 和发布法定人数。" />
          <dl>
            <div><dt>审核单 ID</dt><dd>{selected.caseId}</dd></div>
            <div><dt>定义 ID</dt><dd>{selected.definitionId}</dd></div>
            <div><dt>定义版本</dt><dd>{selected.definitionVersion}</dd></div>
            <div><dt>提交人</dt><dd>{selected.submittedBy}</dd></div>
            <div><dt>所需角色</dt><dd>{selected.requiredRoles.map((role) => ROLE_LABEL[role]).join('、')}</dd></div>
          </dl>
          {Object.keys(selected.approvals).length > 0 ? <RecordTable label="已记录的审核" columns={['角色', '审核人']}>{Object.entries(selected.approvals).map(([role, actor]) => <tr key={role}><td>{ROLE_LABEL[role as ApprovalRole] ?? role}</td><td>{actor}</td></tr>)}</RecordTable> : <p>尚无审核记录</p>}
          <label className="field"><span>审核意见（必填，写入审计，最多 128 字）</span><textarea maxLength={128} value={comment} onChange={(event) => setComment(event.target.value)} placeholder="说明依据与风险判断" /></label>
          <footer className="action-bar action-bar--end modal-actions">
            <Button disabled={decide.isPending} onClick={() => setSelected(null)}>关闭详情</Button>
            <Button tone="danger" disabled={api.demoMode || !availableRole || !comment.trim() || selected.submittedBy === auth.subject || decide.isPending} title={selected.submittedBy === auth.subject ? '提交者不能审批自己的变更' : '确认驳回'} onClick={() => availableRole && decide.mutate({ caseId: selected.caseId, role: availableRole, decision: 'REJECT' })}>驳回</Button>
            <Button tone="primary" disabled={api.demoMode || !availableRole || !comment.trim() || selected.submittedBy === auth.subject || decide.isPending} title={selected.submittedBy === auth.subject ? '提交者不能审批自己的变更' : api.demoMode ? '演示模式不可执行' : '确认批准'} onClick={() => availableRole && decide.mutate({ caseId: selected.caseId, role: availableRole, decision: 'APPROVE' })}><Check size={15} />确认批准</Button>
          </footer>
        </div>
      </Modal>
    )}
  </div>
}
