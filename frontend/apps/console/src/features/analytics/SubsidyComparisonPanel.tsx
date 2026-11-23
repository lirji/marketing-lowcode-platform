import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { displayTimestamp } from '../../components/dateTime'
import { RecordTable } from '../../components/RecordTable'
import { Button, EmptyState, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { api } from '../../shared/api/client'
import { problemDetail } from '../../shared/api/problem'
import type { SubsidyPostingSummary } from '../../shared/api/schemas'
import { displayMinor, type AnalyticsRange } from './analyticsReport'

const REPORT_CURRENCY = 'CNY'
const cny = (minor: number | bigint) => `¥${displayMinor(minor)}`

function comparableDifference(summary: SubsidyPostingSummary, eventCost?: number) {
  if (eventCost === undefined) return { reason: '事件数据水位尚未覆盖所选窗口。' }
  if (!summary.tenantWide) return { reason: '账本仅覆盖当前身份可见的组织和店铺，事件汇总采用租户范围。' }
  if (summary.groups.some(group => group.currency !== REPORT_CURRENCY)) return { reason: '该窗口含其他币种，事件汇总按人民币展示。' }
  if (summary.campaignId && summary.groups.some(group => group.unassigned)) return { reason: '窗口内还有待归属流水，无法确认其中哪些属于本活动。' }
  return { amount: BigInt(eventCost) - summary.groups.reduce((sum, group) => sum + BigInt(group.netAmountMinor), 0n) }
}

export function SubsidyComparisonPanel({ scope, tenantId, range, campaignId, eventCost, eventReadAt }: {
  scope: string; tenantId: string; range: AnalyticsRange; campaignId: string; eventCost?: number; eventReadAt?: string
}) {
  const [requested, setRequested] = useState(false)
  const query = useQuery({
    queryKey: ['analytics-subsidy-comparison', scope, range.from, range.to, campaignId],
    queryFn: () => api.subsidyPostingSummary({ tenantId, from: range.from, to: range.to, campaignId: campaignId || undefined }),
    enabled: requested,
    refetchOnWindowFocus: false,
    retry: false,
  })
  const summary = requested && query.isSuccess && !query.isFetching ? query.data : undefined
  return <Panel className="subsidy-comparison-panel">
    <PanelHeader title="补贴成本对账" aside={<Button disabled={query.isFetching} onClick={() => { if (requested) void query.refetch(); else setRequested(true) }}>{requested ? '刷新账本汇总' : '读取账本汇总'}</Button>} />
    <p className="field-hint">对比所选窗口的事件成本与资金账本。账本按实际入账时间统计，差额可能来自重复事件、入账时点或来源覆盖。</p>
    {requested && query.isFetching && <StateBanner tone="loading" title="正在读取账本汇总" detail="按所选活动和时间范围读取预算确认、退款与冲正。" />}
    {requested && query.isError && <StateBanner tone="error" title="账本汇总读取失败" detail={problemDetail(query.error)} />}
    {summary && <ComparisonResult summary={summary} eventCost={eventCost} eventReadAt={eventReadAt} />}
  </Panel>
}

function ComparisonResult({ summary, eventCost, eventReadAt }: { summary: SubsidyPostingSummary; eventCost?: number; eventReadAt?: string }) {
  const difference = summary.groups.length ? comparableDifference(summary, eventCost) : undefined
  return <>
    <p className="field-hint">账本读取时间 {displayTimestamp(summary.readAt)}。事件读取时间 {displayTimestamp(eventReadAt)}。</p>
    {summary.groups.length === 0 && <EmptyState title="所选范围没有可见的补贴流水" detail="可调整时间和活动后重新查询。" />}
    {difference && <>
      <RecordTable label="补贴成本对账" columns={['来源', '活动归属', '币种', '净额', '账本流水数']}>
        <tr><td>事件投影</td><td>{summary.campaignId ? '所选活动' : '全部活动'}</td><td>{REPORT_CURRENCY}</td><td>{eventCost === undefined ? '待覆盖' : cny(eventCost)}</td><td>—</td></tr>
        {summary.groups.map(group => <tr key={JSON.stringify([group.currency, group.unassigned])}>
          <td>资金账本</td><td>{group.unassigned ? '待归属' : summary.campaignId ? '所选活动' : '已归属'}</td><td>{group.currency}</td>
          <td>{group.currency === REPORT_CURRENCY ? cny(BigInt(group.netAmountMinor)) : `${BigInt(group.netAmountMinor).toLocaleString('zh-CN')}（最小货币单位）`}</td>
          <td>{BigInt(group.postingCount).toLocaleString('zh-CN')}</td>
        </tr>)}
      </RecordTable>
      {difference.amount === undefined
        ? <StateBanner tone="warn" title="暂无法计算可比差额" detail={difference.reason} />
        : <StateBanner tone={difference.amount === 0n ? 'info' : 'warn'} title="事件成本减账本净补贴" detail={cny(difference.amount)} />}
    </>}
  </>
}
