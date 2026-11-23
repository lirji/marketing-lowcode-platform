import { useEffect, useRef } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Badge, Button, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { RecordTable, RecordTime } from '../../components/RecordTable'
import { awardFulfillmentSchema } from '../../shared/api/schemas'
import { api } from '../../shared/api/client'
import { problemDetail } from '../../shared/api/problem'
import { AWARD_ORDER_LABELS } from './awardFulfillmentStatus'

const ORDER_STATUS = awardFulfillmentSchema.shape.centerStatus.unwrap().enum
const SYNC_STATUS = awardFulfillmentSchema.shape.synchronizationState.enum
const SYNC_LABELS = { UNOBSERVED: '尚未查询', PENDING: '等待查询', RUNNING: '查询中', PAUSED: '自动查询已暂停' }

/** 展示带时间的权威查询记录，刷新只查询结果，不发送或撤销发奖。 */
export function AwardFulfillmentPanel({ intentId, onClose }: { intentId: string; onClose: () => void }) {
  const region = useRef<HTMLElement>(null)
  useEffect(() => {
    region.current?.focus()
    region.current?.scrollIntoView?.({ block: 'start' })
  }, [])
  const client = useQueryClient()
  const queryKey = ['award-fulfillment', intentId]
  const query = useQuery({ queryKey, queryFn: () => api.awardFulfillment(intentId) })
  const refresh = useMutation({
    mutationFn: () => api.refreshAwardFulfillment(intentId),
    // 202 是排队时的旧快照，不能覆盖并发 GET 读到的较新观察。
    onSuccess: () => { void client.invalidateQueries({ queryKey }) },
  })
  const value = query.data
  useEffect(() => {
    if (value) void client.invalidateQueries({ queryKey: ['award-intents'] })
  }, [client, value])
  return <section id={`award-fulfillment-${intentId}`} ref={region} tabIndex={-1} aria-label="发放结果详情" className="award-fulfillment-region"><Panel>
    <PanelHeader eyebrow="FULFILLMENT" title="发放结果" aside={<Button onClick={onClose}>关闭结果</Button>} />
    <p>指令 ID：{intentId}</p>
    <p>以下显示最近一次成功查询的结果。权益中心受理后仍需核验发放，后续补偿或撤销可能使结果变化。</p>
    {query.isPending && <p role="status">正在读取查询记录…</p>}
    {query.isError && <StateBanner tone="error" title="查询记录读取失败" detail={problemDetail(query.error)} />}
    {refresh.isError && <StateBanner tone="error" title="请求刷新失败" detail={problemDetail(refresh.error)} />}
    {refresh.isSuccess && <StateBanner tone="info" title="已请求重新查询" detail="任务将在后台执行。稍后点击读取最新记录，查看查询结果。" />}
    {value && <>
      <RecordTable label="履约观察" columns={['权益订单号', '最近发放结果', '查询时间（北京时间）', '同步状态', '连续查询失败次数']}>
        <tr><td>{value.benefitOrderNo ?? '尚未查到'}</td>
          <td><Badge tone={value.centerStatus === ORDER_STATUS.SUCCEEDED ? 'good' : value.centerStatus === ORDER_STATUS.FAILED ? 'danger' : 'info'}>{value.centerStatus ? AWARD_ORDER_LABELS[value.centerStatus] : '尚无可信结果'}</Badge></td>
          <td><RecordTime value={value.checkedAt} /></td><td>{SYNC_LABELS[value.synchronizationState]}</td><td>{value.consecutiveFailures}</td></tr>
      </RecordTable>
      {value.errorCode && <StateBanner tone="warn" title="最近一次查询未成功" detail={`${value.errorCode}。保留之前的查询结果；该错误不代表权益发放失败或已取消。`} />}
      {!value.refreshEnabled && <StateBanner tone="info" title="履约查询尚未启用" detail="需先完成权益中心读取权限配置。现有投递状态不会被当作到账结果。" />}
      {value.items.length > 0 && <RecordTable label="权益项结果" columns={['项 ID', 'SKU ID', 'SKU 版本', '数量', '权益项状态']}>
        {value.items.map((item) => <tr key={item.clientItemId}><td>{item.clientItemId}</td><td>{item.skuId}</td><td>{item.skuVersion ?? '未提供'}</td><td>{item.quantity}</td><td>{item.status}</td></tr>)}
      </RecordTable>}
    </>}
    <div className="award-fulfillment-actions">
      <Button onClick={() => void query.refetch()} disabled={query.isFetching || refresh.isPending}>读取最新记录</Button>
      <Button onClick={() => refresh.mutate()} disabled={!value?.refreshEnabled || refresh.isPending || query.isFetching || value.synchronizationState === SYNC_STATUS.RUNNING}>重新查询权益中心</Button>
    </div>
  </Panel></section>
}
