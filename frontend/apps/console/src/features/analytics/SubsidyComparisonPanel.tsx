import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { displayTimestamp } from '../../components/dateTime'
import { RecordTable } from '../../components/RecordTable'
import { Button, EmptyState, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { api } from '../../shared/api/client'
import { problemDetail } from '../../shared/api/problem'
import type { SubsidyPostingSummary } from '../../shared/api/schemas'
import { downloadCsv, formatCsv } from '../../shared/downloadCsv'
import { displayMinor, type AnalyticsRange } from './analyticsReport'

const REPORT_CURRENCY = 'CNY'
const cny = (minor: number | bigint) => `¥${displayMinor(minor)}`
const COLUMNS = [
  { label: '来源', width: 110 }, { label: '活动归属', width: 110 }, { label: '币种', width: 76 },
  { label: '补贴确认', width: 148 }, { label: '退款或冲正', width: 148 }, { label: '净额', width: 148 },
  { label: '账本流水数', width: 110 },
]

function ledgerAmount(minor: string | undefined, currency: string) {
  if (minor === undefined) return '—'
  const amount = BigInt(minor)
  return currency === REPORT_CURRENCY ? cny(amount) : `${amount.toLocaleString('zh-CN')}（最小货币单位）`
}

function downloadLedgerSummary(summary: SubsidyPostingSummary) {
  const content = formatCsv([
    ['租户 ID', '授权范围', '活动筛选 ID', '入账开始（UTC，含）', '入账结束（UTC，不含）', '读取时间（UTC）',
      '活动归属', '币种', '补贴确认（最小货币单位）', '退款或冲正（最小货币单位）', '净额（最小货币单位）',
      '账本流水数', '待核查关联流水数（同窗口）'],
    ...summary.groups.map(group => [summary.tenantId, summary.tenantWide ? '租户' : '授权组织与门店', summary.campaignId ?? '',
      summary.from, summary.to, summary.readAt, group.unassigned ? '待归属' : '已归属', group.currency,
      group.breakdown ? BigInt(group.breakdown.confirmedAmountMinor) : '',
      group.breakdown ? BigInt(group.breakdown.reversedAmountMinor) : '', BigInt(group.netAmountMinor),
      BigInt(group.postingCount), summary.unmatchedPostingCount == null ? '当前范围未返回' : BigInt(summary.unmatchedPostingCount)]),
  ])
  downloadCsv(content, `subsidy-ledger-summary-${new Date(summary.to).toISOString().replaceAll(':', '-')}.csv`)
}

function coverageIssue(summary: SubsidyPostingSummary) {
  if (!summary.tenantWide) return { reason: '账本仅覆盖当前身份可见的组织和店铺，事件汇总采用租户范围。' }
  if (summary.unmatchedPostingCount == null) return { reason: '当前账本接口尚未返回流水关联校验结果。' }
  if (summary.unmatchedPostingCount !== '0') {
    return { reason: `该窗口有 ${BigInt(summary.unmatchedPostingCount).toLocaleString('zh-CN')} 笔流水缺少有效的资金关联，账本净额可能不完整。` }
  }
}

function comparableDifference(summary: SubsidyPostingSummary, eventCost?: number): { reason: string } | { amount: bigint } {
  const issue = coverageIssue(summary)
  if (issue) return issue
  if (eventCost === undefined) return { reason: '事件数据水位尚未覆盖所选窗口。' }
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
  const hasPostings = summary.groups.length > 0
  const breakdownAvailable = summary.groups.every(group => group.breakdown !== undefined)
  const difference = hasPostings ? comparableDifference(summary, eventCost) : coverageIssue(summary)
  return <>
    <p className="field-hint">账本读取时间 {displayTimestamp(summary.readAt)}。事件读取时间 {displayTimestamp(eventReadAt)}。</p>
    {!hasPostings && !difference && <EmptyState title="所选范围没有可见的补贴流水" detail="可调整时间和活动后重新查询。" />}
    {hasPostings &&
      <RecordTable label="补贴成本对账" columns={COLUMNS}>
        <tr><td>事件投影</td><td>{summary.campaignId ? '所选活动' : '全部活动'}</td><td>{REPORT_CURRENCY}</td><td>—</td><td>—</td><td>{eventCost === undefined ? '待覆盖' : cny(eventCost)}</td><td>—</td></tr>
        {summary.groups.map(group => <tr key={JSON.stringify([group.currency, group.unassigned])}>
          <td>资金账本</td><td>{group.unassigned ? '待归属' : summary.campaignId ? '所选活动' : '已归属'}</td><td>{group.currency}</td>
          <td>{ledgerAmount(group.breakdown?.confirmedAmountMinor, group.currency)}</td>
          <td>{ledgerAmount(group.breakdown?.reversedAmountMinor, group.currency)}</td>
          <td>{ledgerAmount(group.netAmountMinor, group.currency)}</td>
          <td>{BigInt(group.postingCount).toLocaleString('zh-CN')}</td>
        </tr>)}
      </RecordTable>
    }
    {!breakdownAvailable && <p className="field-hint">当前资金服务未返回账本分项，表中仍可核对净额。</p>}
    {hasPostings && breakdownAvailable && <>
      <div className="action-bar toolbar"><Button onClick={() => downloadLedgerSummary(summary)} aria-describedby="ledger-summary-export-hint">导出账本汇总</Button></div>
      <p className="field-hint" id="ledger-summary-export-hint">CSV 金额按最小货币单位输出。可将金额列按文本导入，以保留完整精度。</p>
    </>}
    {difference && ('amount' in difference
      ? <StateBanner tone={difference.amount === 0n ? 'info' : 'warn'} title="事件成本减账本净补贴" detail={cny(difference.amount)} />
      : <StateBanner tone="warn" title="暂无法计算可比差额" detail={difference.reason} />)}
  </>
}
