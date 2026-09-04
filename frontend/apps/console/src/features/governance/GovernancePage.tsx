import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Eye, ShieldAlert, UserRoundCheck } from 'lucide-react'
import { Badge, Button, DemoBanner, EmptyState, Modal, PageHeader, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { problemDetail } from '../../shared/api/problem'
import { api } from '../../shared/api/client'
import { useAuth } from '../../shared/auth/useAuth'
import type { ApprovalRole, ApprovalView } from '../../shared/api/schemas'

const ROLE_LABEL: Record<ApprovalRole, string> = { BUSINESS: '业务', FINANCE: '资金', COMPLIANCE: '合规', MERCHANT: '商家' }

export function GovernancePage() {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const [selected, setSelected] = useState<ApprovalView | null>(null)
  const [comment, setComment] = useState('')
  const [notice, setNotice] = useState('')
  const approvals = useQuery({
    queryKey: ['approvals'],
    queryFn: api.demoMode ? async (): Promise<ApprovalView[]> => [] : api.approvals,
  })
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
  const openItems = (approvals.data ?? []).filter((item) => item.status === 'OPEN')
  const roleFor = (item: ApprovalView | null) => {
    const roles = item?.requiredRoles.length
      ? item.requiredRoles
      : (['BUSINESS', 'FINANCE', 'COMPLIANCE', 'MERCHANT'] as ApprovalRole[])
    return roles.find((role) => auth.hasPermission(`approval:${role.toLowerCase()}`))
  }
  const availableRole = roleFor(selected)

  return <div className="workspace governance-page">
    <PageHeader eyebrow="治理与审核" title="让高风险变更经得起追问。" description="提交者不能审批自己的变更；业务、资金、风控和合规按风险等级形成法定人数。" actions={<Button disabled title="风险策略尚未接入控制面"><ShieldAlert size={15} />风险策略</Button>} />
    <DemoBanner />
    {notice && <StateBanner tone="success" title="审核决定已记录" detail={notice} />}
    {approvals.isError && <StateBanner tone="error" title="审核队列加载失败" detail={problemDetail(approvals.error)} />}
    <div className="governance-layout">
      <Panel>
        <PanelHeader eyebrow="MY QUEUE" title="待我审核" aside={<Badge tone="warn">{openItems.length} 项</Badge>} />
        {api.demoMode ? <EmptyState title="演示模式不展示真实审核单" detail="关闭 DEMO_MODE 后将读取 /api/v1/approvals。" /> : openItems.length === 0 ? <EmptyState title="没有待审核变更" detail="新的提交会按职责分离进入此队列。" /> : <div className="approval-list">{openItems.map((item) => <button key={item.caseId} type="button" onClick={() => { setSelected(item); setComment('') }}><span className="risk-level medium">{item.status}</span><div><strong>{item.definitionId} v{item.definitionVersion}</strong><small>{item.caseId} · 提交人 {item.submittedBy}</small></div><div><strong>{item.requiredRoles.map((role) => ROLE_LABEL[role]).join(' · ')}</strong><small>已批准 {Object.keys(item.approvals).length}/{item.requiredRoles.length}</small></div><Eye size={15} /></button>)}</div>}
      </Panel>
      <aside>
        <Panel>
          <PanelHeader eyebrow="SEPARATION OF DUTIES" title="职责分离" />
          <div className="role-matrix">
            {(['BUSINESS', 'FINANCE', 'COMPLIANCE', 'MERCHANT'] as ApprovalRole[]).map((role) => {
              const allowed = auth.hasPermission(`approval:${role.toLowerCase()}`)
              return <div key={role}><UserRoundCheck size={14} /><span><strong>{ROLE_LABEL[role]}负责人</strong><small>{allowed ? auth.displayName : '无此权限'}</small></span><Badge tone={allowed ? 'good' : 'danger'}>{allowed ? '可审核' : '不可审批'}</Badge></div>
            })}
          </div>
        </Panel>
        <Panel>
          <PanelHeader eyebrow="QUEUE" title="今日治理态势" />
          <dl className="summary-list">
            <div><dt>待审</dt><dd>{openItems.length}</dd></div>
            <div><dt>已批准</dt><dd>{(approvals.data ?? []).filter((item) => item.status === 'APPROVED').length}</dd></div>
            <div><dt>已驳回</dt><dd>{(approvals.data ?? []).filter((item) => item.status === 'REJECTED').length}</dd></div>
          </dl>
        </Panel>
      </aside>
    </div>
    {selected && (
      <Modal title="审核高风险变更" description={`${selected.caseId} · ${selected.definitionId}`} onClose={() => setSelected(null)}>
        <div className="approval-detail">
          {decide.isError && <StateBanner tone="error" title="审核失败" detail={problemDetail(decide.error)} />}
          <StateBanner tone="warn" title="批准后不会立即生效" detail="仍需编译、签名、运行时 ACK 和发布法定人数。" />
          <dl>
            <div><dt>提交人</dt><dd>{selected.submittedBy}</dd></div>
            <div><dt>所需角色</dt><dd>{selected.requiredRoles.map((role) => ROLE_LABEL[role]).join('、')}</dd></div>
            <div><dt>已记录</dt><dd>{Object.entries(selected.approvals).map(([role, actor]) => `${ROLE_LABEL[role as ApprovalRole] ?? role}:${actor}`).join('；') || '尚无'}</dd></div>
          </dl>
          <label className="field"><span>审核意见（必填，写入审计，最多 128 字）</span><textarea maxLength={128} value={comment} onChange={(event) => setComment(event.target.value)} placeholder="说明依据与风险判断" /></label>
          <footer className="modal-actions">
            <Button onClick={() => setSelected(null)}>取消</Button>
            <Button tone="danger" disabled={api.demoMode || !availableRole || !comment.trim() || selected.submittedBy === auth.subject || decide.isPending} title={selected.submittedBy === auth.subject ? '提交者不能审批自己的变更' : '确认驳回'} onClick={() => availableRole && decide.mutate({ caseId: selected.caseId, role: availableRole, decision: 'REJECT' })}>驳回</Button>
            <Button tone="primary" disabled={api.demoMode || !availableRole || !comment.trim() || selected.submittedBy === auth.subject || decide.isPending} title={selected.submittedBy === auth.subject ? '提交者不能审批自己的变更' : api.demoMode ? '演示模式不可执行' : '确认批准'} onClick={() => availableRole && decide.mutate({ caseId: selected.caseId, role: availableRole, decision: 'APPROVE' })}><Check size={15} />确认批准</Button>
          </footer>
        </div>
      </Modal>
    )}
  </div>
}
