import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { DateTimePicker } from '../../components/DateTimePicker'
import { FieldHint } from '../../components/FieldHint'
import { RecordTable, RecordTime } from '../../components/RecordTable'
import { Badge, Button, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { api } from '../../shared/api/client'
import { ScheduleState, type BundleSchedule, type BundleScheduleInput, type ReleaseBundle } from '../../shared/api/releaseBundles'
import { problemDetail } from '../../shared/api/problem'
import { useAuth } from '../../shared/auth/useAuth'
import { uncertainWrite } from './bundleErrors'

const states = { SCHEDULED: '已预约', PAUSED: '已暂停', RUNNING: '执行中', APPLIED: '已生效', REJECTED: '已拒绝', EXPIRED: '已过期' }
type ScheduledIntent = { input: BundleScheduleInput; key: string }

export function BundleSchedules({ bundle, scope, appliedSequence, canAdvance, onRefresh, onTrack }: {
  bundle: ReleaseBundle; scope: string; appliedSequence?: number; canAdvance: boolean; onRefresh: () => Promise<void>; onTrack: (id: string) => void
}) {
  const auth = useAuth()
  const canManage = auth.hasPermission('release:activate')
  const [dueAt, setDueAt] = useState('')
  const lastObservedApplied = useRef<string | undefined>(undefined)
  const schedules = useQuery({ queryKey: ['bundle-schedules', scope, bundle.campaignId, bundle.version],
    queryFn: () => api.bundleSchedules(bundle.campaignId, bundle.version), retry: false,
    refetchInterval: query => {
      const entries = query.state.data ?? []
      if (entries.some(item => item.state === ScheduleState.RUNNING)) return 5000
      const next = Math.min(...entries.filter(item => item.state === ScheduleState.SCHEDULED).map(item => Date.parse(item.dueAt)))
      return Number.isFinite(next) ? Math.max(5000, Math.min(3600000, next - Date.now())) : false
    },
  })
  // 调度在浏览器外执行，观察到新的真实应用回执后也要刷新页面上方的实际生效位置。
  const observedApplied = schedules.data?.find(item => item.state === ScheduleState.APPLIED)?.scheduleId
  useEffect(() => {
    if (observedApplied && lastObservedApplied.current !== observedApplied) {
      lastObservedApplied.current = observedApplied
      void onRefresh()
    }
  }, [observedApplied, onRefresh])
  const refresh = async () => { await Promise.all([schedules.refetch(), onRefresh()]) }
  const schedule = useMutation({
    mutationFn: (intent: ScheduledIntent) => api.scheduleBundle(bundle.campaignId, intent.input, intent.key),
    onSuccess: () => setDueAt(''), onSettled: refresh,
  })
  const change = useMutation({ mutationFn: ({ item, action }: { item: BundleSchedule; action: 'pause' | 'resume' }) =>
    api.changeBundleSchedule(bundle.campaignId, item.scheduleId, action, item.revision), onSettled: refresh })
  const unknown = schedule.isError && uncertainWrite(schedule.error)
  const busy = change.isPending || schedule.isPending
  const expiresAt = Math.min(...bundle.members.map(member => Date.parse(member.expiresAt)))
  const validTime = Date.parse(dueAt) > Date.now() && Date.parse(dueAt) < expiresAt
  const submit = () => {
    if (!canManage || !validTime || appliedSequence === undefined || !canAdvance || busy || unknown) return
    change.reset()
    schedule.mutate({ key: crypto.randomUUID(), input: { version: bundle.version, dueAt, expectedAppliedSequence: appliedSequence } })
  }
  return <Panel><PanelHeader title="定时生效" aside={<Button disabled={busy || schedules.isFetching} onClick={() => void refresh()}>刷新调度记录</Button>} />
    {canManage && <div className="action-bar action-bar--fields bundle-inline">
      <div className="field"><span>计划生效时间</span>
        <DateTimePicker label="计划生效时间" value={dueAt} disabled={busy || unknown} invalid={!!dueAt && !validTime} describedBy="schedule-hint"
          onChange={value => { setDueAt(value); schedule.reset() }} />
        <FieldHint id="schedule-hint">按北京时间预约 v{bundle.version}，到期时重新核对审批、运行回执和已应用序号。</FieldHint></div>
      <Button disabled={busy || unknown || !validTime || !canAdvance || appliedSequence === undefined} onClick={submit}>预约生效 v{bundle.version}</Button>
    </div>}
    {dueAt && !validTime && <StateBanner tone="warn" title="计划时间不在可发布窗口" detail="请选择将来且早于组合有效期的北京时间。" />}
    {schedule.isSuccess && <StateBanner tone="success" title="调度已登记" detail={`调度 ID ${schedule.data.scheduleId}，请在记录中查看执行结果。`} />}
    {schedule.isError && <StateBanner tone={unknown ? 'warn' : 'error'} title={unknown ? '预约结果待确认' : '预约失败'} detail={problemDetail(schedule.error)} />}
    {unknown && schedule.variables && <Button disabled={!canManage || busy} onClick={() => schedule.mutate(schedule.variables!)}>用原请求确认预约</Button>}
    {change.isError && <StateBanner tone="error" title="调度变更待核对" detail={problemDetail(change.error)} />}
    {change.isSuccess && <p role="status">已收到调度回执 · {states[change.data.state]} · 修订 {change.data.revision}</p>}
    {schedules.isLoading && <p role="status">正在读取定时任务…</p>}
    {schedules.isError && <StateBanner tone="error" title="调度记录读取失败" detail={problemDetail(schedules.error)} />}
    {schedules.isSuccess && <><p className="bundle-note">{schedules.data.length ? '本版本最近 20 条调度记录' : '此版本暂无调度记录'}</p>
      {schedules.data.length > 0 && <RecordTable actions label="定时发布记录" columns={['调度 ID', '状态', '计划时间（北京时间）', '最迟生效时间（北京时间）', '预期生效序号', '修订', '原因代码', '操作']}>
        {schedules.data.map(item => <tr key={item.scheduleId}><td>{item.scheduleId}</td><td><Badge tone={item.state === ScheduleState.APPLIED ? 'good' : item.state === ScheduleState.REJECTED || item.state === ScheduleState.EXPIRED ? 'danger' : 'info'}>{states[item.state]}</Badge></td>
          <td><RecordTime value={item.dueAt} /></td><td><RecordTime value={item.applyBefore} /></td><td>{item.expectedAppliedSequence}</td><td>{item.revision}</td><td>{item.reasonCode ?? '—'}</td>
          <td><div className="row-actions">
            {canManage && item.state === ScheduleState.SCHEDULED && <Button disabled={busy || schedules.isFetching} onClick={() => change.mutate({ item, action: 'pause' })}>暂停预约</Button>}
            {canManage && item.state === ScheduleState.PAUSED && <Button disabled={busy || schedules.isFetching || Date.parse(item.applyBefore) <= Date.now()} onClick={() => change.mutate({ item, action: 'resume' })}>恢复预约</Button>}
            {item.operationId && <Button onClick={() => onTrack(item.operationId!)}>查看发布操作</Button>}
            {!item.operationId && (!canManage || item.state !== ScheduleState.SCHEDULED && item.state !== ScheduleState.PAUSED) && '—'}
          </div></td></tr>)}</RecordTable>}</>}
  </Panel>
}
