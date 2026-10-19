import { RecordTable, RecordTime } from '../../components/RecordTable'
import { Select } from '../../components/Select'
import { PageTabs } from '../../components/PageTabs'
import { useEffect, useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { Badge, Button, EmptyState, PageHeader, Panel, StateBanner } from '../../components/ui'
import { api } from '../../shared/api/client'
import { problemDetail } from '../../shared/api/problem'
import { useAuth } from '../../shared/auth/useAuth'
import { deliveryLabel, formatNullableCount, qualificationLabel, watermarkText } from './referralOperationsStatus'

const TABS = [
  { id: 'participants', label: '参与人' },
  { id: 'relations', label: '关系 / 资格' },
  { id: 'rewards', label: '奖励' },
  { id: 'exceptions', label: '异常' },
] as const

const PAGE_SIZE = 20

export function ReferralOperationsPage() {
  const auth = useAuth()
  const [params, setParams] = useSearchParams()
  const tab = (TABS.find((item) => item.id === params.get('tab')) ?? TABS[0]).id
  const campaignId = params.get('campaignId')?.trim() ?? ''
  const [after, setAfter] = useState<string>()
  const live = !api.demoMode
  const campaigns = useQuery({
    queryKey: ['campaigns', auth.tenantId, auth.organizations, auth.shops],
    queryFn: api.campaigns,
    enabled: live,
  })
  const options = useMemo(() => {
    const rows = campaigns.data ?? []
    const referral = rows.filter((item) => item.campaignType === 'REFERRAL')
    return referral.length > 0 ? referral : rows
  }, [campaigns.data])

  const scopeKey = `${auth.tenantId}|${auth.organizations.join(',')}|${auth.shops.join(',')}`
  useEffect(() => {
    setAfter(undefined)
  }, [campaignId, tab, scopeKey])

  const enabled = live && Boolean(campaignId) && tab !== 'exceptions'
  const participants = useQuery({
    queryKey: ['referral-ops', 'participants', auth.tenantId, auth.organizations, auth.shops, campaignId, after],
    queryFn: () => api.referralParticipants(campaignId, after, PAGE_SIZE),
    enabled: enabled && tab === 'participants',
  })
  const relations = useQuery({
    queryKey: ['referral-ops', 'relations', auth.tenantId, auth.organizations, auth.shops, campaignId, after],
    queryFn: () => api.referralRelations(campaignId, after, PAGE_SIZE),
    enabled: enabled && tab === 'relations',
  })
  const rewards = useQuery({
    queryKey: ['referral-ops', 'rewards', auth.tenantId, auth.organizations, auth.shops, campaignId, after],
    queryFn: () => api.referralRewards(campaignId, after, PAGE_SIZE),
    enabled: enabled && tab === 'rewards',
  })
  const active = tab === 'participants' ? participants : tab === 'relations' ? relations : rewards
  const watermark = enabled
    ? watermarkText(active.data?.asOf, active.data?.consistency)
    : '数据水位：未知'

  const setTab = (next: (typeof TABS)[number]['id']) => {
    const copy = new URLSearchParams(params)
    copy.set('tab', next)
    setParams(copy, { replace: true })
  }
  const setCampaign = (next: string) => {
    const copy = new URLSearchParams(params)
    if (next) copy.set('campaignId', next)
    else copy.delete('campaignId')
    setParams(copy, { replace: true })
  }

  return (
    <div className="workspace referral-ops">
      <PageHeader
        eyebrow="裂变运营"
        title="邀请有礼运营台"
        description="参与人、邀请关系和奖励来自裂变主库只读查询。不显示主体原文，不把 BOUND、受理或 ACCEPTED 当成到账。异常汇总接口尚未交付。"
      />
      {!live && <StateBanner tone="warn" title="演示模式" detail="DEMO_MODE 不会查询裂变主库。关闭后按活动读取真实参与、关系和奖励。" />}
      {live && campaigns.isError && <StateBanner tone="error" title="活动列表加载失败" detail={problemDetail(campaigns.error)} />}
      <div className="toolbar">
        <label className="compact-select">
          <span className="sr-only">活动</span>
          <Select aria-label="活动" value={campaignId} disabled={!live} onChange={(event) => setCampaign(event.target.value)}>
            <option value="">选择活动</option>
            {options.map((item) => (
              <option key={item.id} value={item.id}>{item.name} · {item.id}</option>
            ))}
          </Select>
        </label>
      </div>
      {live && campaigns.data && campaigns.data.length > 0 && !campaigns.data.some((item) => item.campaignType === 'REFERRAL') && (
        <StateBanner tone="info" title="未返回 campaignType" detail="后端尚未持久化活动类型时列出全部活动，不会把名称当成裂变类型。" />
      )}
      <PageTabs label="裂变运营页签" items={TABS.map((item) => ({ value: item.id, label: item.label }))} value={tab} onChange={setTab}>
      <Panel className="table-panel">
        {tab === 'exceptions' ? (
          <EmptyState title="异常待接入" detail="BE-03 的 summary / 异常合同尚未开放。缺接口保持待接入，不显示假成功。" />
        ) : !live ? (
          <EmptyState title="未查询真实数据" detail="演示模式没有参与人、关系或奖励行。" />
        ) : !campaignId ? (
          <EmptyState title="先选择活动" detail="三类查询都按 campaignId 隔离。切换活动会丢弃上一页游标。" />
        ) : active.isPending ? (
          <StateBanner tone="loading" title="正在读取主库" detail="返回值以 OpenAPI/DTO 解析，不会把空进度显示成已验证的 0。" />
        ) : active.isError ? (
          <StateBanner tone="error" title="运营查询失败" detail={problemDetail(active.error)} />
        ) : (active.data?.items.length ?? 0) === 0 ? (
          <EmptyState title="没有记录" detail="当前租户、组织和门店范围内没有返回行。" />
        ) : tab === 'participants' ? (
          <RecordTable label="参与人" columns={['参与人 ID', '活动 ID', '状态', '组织 ID', '店铺 ID', '定义 ID', '定义版本', '发布代际', '当前有效人数', '历史曾合格人数', '进度修订', '创建时间（北京时间）']}>
            {participants.data?.items.map((row) => <tr key={row.participantId}>
              <td>{row.participantId}</td><td>{row.campaignId}</td><td><Badge>{row.state}</Badge></td><td>{row.organizationId}</td><td>{row.shopId || '全部店铺'}</td><td>{row.definitionId}</td><td>{row.definitionVersion}</td><td>{row.generation}</td><td>{formatNullableCount(row.validCount)}</td><td>{formatNullableCount(row.everQualifiedCount)}</td><td>{formatNullableCount(row.progressRevision)}</td><td><RecordTime value={row.createdAt} /></td>
            </tr>)}
          </RecordTable>
        ) : tab === 'relations' ? (
          <RecordTable label="邀请关系" columns={['关系 ID', '参与人 ID', '绑定状态', '资格状态', '是否计入人数', '资格原因', '曾经合格', '资格修订', '证据版本', '绑定时间（北京时间）', '观察截止（北京时间）']}>
            {relations.data?.items.map((row) => <tr key={row.relationId}>
              <td>{row.relationId}</td><td>{row.participantId}</td><td><Badge>{row.state}</Badge></td><td>{qualificationLabel(row.qualificationState, undefined)}</td><td>{row.counted === true ? '是' : row.counted === false ? '否' : '未知'}</td><td>{row.reason ?? '无原因'}</td><td>{row.everQualified === true ? '曾合格' : row.everQualified === false ? '未曾合格' : '未知'}</td><td>{formatNullableCount(row.qualificationRevision)}</td><td>{formatNullableCount(row.evidenceVersion)}</td><td><RecordTime value={row.boundAt} /></td><td><RecordTime value={row.deadlineAt} /></td>
            </tr>)}
          </RecordTable>
        ) : (
          <RecordTable label="裂变奖励" columns={['奖励 ID', '参与人 ID', '关系 ID', '奖励角色', '奖励模式', '规则 ID', '门槛', '资格状态', '授权状态', '风控状态', '履约状态', '配额状态', '补偿状态', '修订版本', '创建时间（北京时间）']}>
            {rewards.data?.items.map((row) => <tr key={row.rewardId}>
              <td>{row.rewardId}</td><td>{row.participantId}</td><td>{row.relationId ?? '—'}</td><td>{row.role}</td><td>{row.mode}</td><td>{row.ruleId}</td><td>{row.threshold}</td><td><Badge>{row.entitlementState}</Badge></td><td><Badge>{row.authorizationState}</Badge></td><td><Badge>{row.riskState}</Badge></td><td>{deliveryLabel(row.deliveryState)}</td><td>{row.quotaState}</td><td>{row.compensationState}</td><td>{row.revision}</td><td><RecordTime value={row.createdAt} /></td>
            </tr>)}
          </RecordTable>
        )}
        {enabled && active.data ? (
          <div className="pager">
            <Button disabled={!after} onClick={() => setAfter(undefined)}>回到首页</Button>
            <Button disabled={!active.data.nextCursor} onClick={() => { if (active.data?.nextCursor) setAfter(active.data.nextCursor) }}>下一页</Button>
          </div>
        ) : null}
      </Panel>
      <p className="muted">{watermark}</p>
      </PageTabs>
    </div>
  )
}
