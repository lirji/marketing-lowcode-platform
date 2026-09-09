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
          <select aria-label="活动" value={campaignId} disabled={!live} onChange={(event) => setCampaign(event.target.value)}>
            <option value="">选择活动</option>
            {options.map((item) => (
              <option key={item.id} value={item.id}>{item.name} · {item.id}</option>
            ))}
          </select>
        </label>
      </div>
      {live && campaigns.data && campaigns.data.length > 0 && !campaigns.data.some((item) => item.campaignType === 'REFERRAL') && (
        <StateBanner tone="info" title="未返回 campaignType" detail="后端尚未持久化活动类型时列出全部活动，不会把名称当成裂变类型。" />
      )}
      <div className="page-tabs" role="tablist" aria-label="裂变运营页签">
        {TABS.map((item) => (
          <button
            key={item.id}
            type="button"
            role="tab"
            aria-selected={tab === item.id}
            className={tab === item.id ? 'active' : ''}
            onClick={() => setTab(item.id)}
          >
            {item.label}
          </button>
        ))}
      </div>
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
          <table className="data-table">
            <thead><tr><th>参与人</th><th>状态 / 版本</th><th>当前有效人数</th><th>历史曾合格</th></tr></thead>
            <tbody>
              {participants.data?.items.map((row) => (
                <tr key={row.participantId}>
                  <td><strong className="mono">{row.participantId}</strong><small>{row.organizationId} / {row.shopId}</small></td>
                  <td><Badge>{row.state}</Badge><small>定义 {row.definitionId}@{row.definitionVersion} · gen {row.generation}</small></td>
                  <td>{formatNullableCount(row.validCount)}<small>进度修订 {formatNullableCount(row.progressRevision)}</small></td>
                  <td>{formatNullableCount(row.everQualifiedCount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : tab === 'relations' ? (
          <table className="data-table">
            <thead><tr><th>关系</th><th>绑定</th><th>资格</th><th>证据</th></tr></thead>
            <tbody>
              {relations.data?.items.map((row) => (
                <tr key={row.relationId}>
                  <td><strong className="mono">{row.relationId}</strong><small>参与 {row.participantId}</small></td>
                  <td>{row.state}<small>绑定 {row.boundAt}{row.deadlineAt ? ` · 观察截止 ${row.deadlineAt}` : ''}</small></td>
                  <td>{qualificationLabel(row.qualificationState, row.counted)}<small>{row.reason ?? '无原因'} · 修订 {formatNullableCount(row.qualificationRevision)}</small></td>
                  <td>{row.everQualified === true ? '曾合格' : row.everQualified === false ? '未曾合格' : '未知'}<small>证据版本 {formatNullableCount(row.evidenceVersion)}</small></td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <table className="data-table">
            <thead><tr><th>奖励</th><th>规则</th><th>资格 / 授权 / 风控</th><th>履约 / 配额 / 追回</th></tr></thead>
            <tbody>
              {rewards.data?.items.map((row) => (
                <tr key={row.rewardId}>
                  <td><strong className="mono">{row.rewardId.slice(0, 12)}…</strong><small>{row.role} · {row.mode}{row.relationId ? ` · 关系 ${row.relationId}` : ' · 阶梯'}</small></td>
                  <td>{row.ruleId}<small>门槛 {row.threshold} · 修订 {row.revision}</small></td>
                  <td>{row.entitlementState} / {row.authorizationState} / {row.riskState}</td>
                  <td>{deliveryLabel(row.deliveryState)}<small>配额 {row.quotaState} · 补偿 {row.compensationState}</small></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {enabled && active.data ? (
          <div className="pager">
            <Button disabled={!after} onClick={() => setAfter(undefined)}>回到首页</Button>
            <Button disabled={!active.data.nextCursor} onClick={() => { if (active.data?.nextCursor) setAfter(active.data.nextCursor) }}>下一页</Button>
          </div>
        ) : null}
      </Panel>
      <p className="muted">{watermark}</p>
    </div>
  )
}
