import { useEffect, useRef, useState } from 'react'
import { Select } from '../../components/Select'
import { displayTimestamp } from '../../components/dateTime'
import { Badge, Button, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { api, ApiProblem } from '../../shared/api/client'
import { newDmnTable, type DmnDraft, type DmnTable } from '../../shared/api/dmn'
import { useAuth } from '../../shared/auth/useAuth'
import type { Campaign } from '../../shared/model/types'

const DRAFT_NOT_FOUND = 'DMN_DRAFT_NOT_FOUND'
const VERSION_CONFLICT = 'DMN_DRAFT_VERSION_CONFLICT'
const TEXT = {
  failed: '草稿请求失败，请保留当前修改后重试。',
  conflict: '服务端已有新版本，当前修改已保留。请核对后选择「加载最新版本」；加载会替换当前编辑内容。',
  editedDuringLoad: '加载期间您修改了表格，已保留当前输入。请确认后重新加载最新版本。',
}

/** 数据库版本是唯一保存依据；计算结果、测试输入不属于草稿内容。 */
export function DmnDraftStorage({ table, onRestore }: { table: DmnTable; onRestore: (table: DmnTable) => void }) {
  const auth = useAuth()
  const canRead = auth.hasPermission('definition:read')
  const canWrite = auth.hasPermission('definition:write') && !api.demoMode
  const canList = auth.hasPermission('campaign:read')
  const [campaigns, setCampaigns] = useState<Campaign[]>([])
  const [listError, setListError] = useState('')
  const [listAttempt, setListAttempt] = useState(0)
  const [campaignId, setCampaignId] = useState('')
  const [saved, setSaved] = useState<DmnDraft>()
  const [baseline, setBaseline] = useState(() => JSON.stringify(newDmnTable()))
  const [ready, setReady] = useState(false)
  const [busy, setBusy] = useState<'load' | 'save'>()
  const [error, setError] = useState('')
  const [pending, setPending] = useState<string>()
  const [conflict, setConflict] = useState(false)
  const fingerprint = JSON.stringify(table)
  const currentFingerprint = useRef(fingerprint)
  // 只在同一 payload 下复用请求键，网络中断重试不会创建重复版本。
  const retry = useRef<{ payload: string; key: string } | undefined>(undefined)
  const epoch = useRef(0)
  const alive = useRef(true)
  const bound = useRef(false)
  useEffect(() => { currentFingerprint.current = fingerprint }, [fingerprint])
  useEffect(() => { alive.current = true; return () => { alive.current = false; epoch.current++ } }, [])
  useEffect(() => {
    if (!canList) return
    let active = true
    api.campaigns().then((items) => { if (active) { setCampaigns(items); setListError('') } })
      .catch((cause) => { if (active) setListError(cause instanceof Error ? cause.message : TEXT.failed) })
    return () => { active = false }
  }, [canList, listAttempt])

  const dirty = fingerprint !== baseline
  useEffect(() => {
    if (!dirty) return
    const beforeUnload = (event: BeforeUnloadEvent) => { event.preventDefault() }
    window.addEventListener('beforeunload', beforeUnload)
    return () => window.removeEventListener('beforeunload', beforeUnload)
  }, [dirty])

  const load = async (id: string, firstBinding = false) => {
    if (!id || !canRead || busy) return
    const requestEpoch = ++epoch.current
    const started = currentFingerprint.current
    setCampaignId(id); setReady(false); setBusy('load'); setError(''); setPending(undefined); setConflict(false)
    setSaved(undefined); retry.current = undefined
    const accept = (draft?: DmnDraft) => {
      if (!alive.current || epoch.current !== requestEpoch) return
      // 首次404只是建立归属，不替换表格，加载期间的新编辑也可安全保留。
      if (firstBinding && !draft) { bound.current = true; setBaseline(JSON.stringify(newDmnTable())); setReady(true); return }
      if (started !== currentFingerprint.current) { setError(TEXT.editedDuringLoad); return }
      // 未绑定草稿可直接归属尚无版本的活动；目标已有内容时才需要明确放弃当前编辑。
      if (firstBinding && dirty && draft) { setCampaignId(''); setPending(id); return }
      const next = draft?.table ?? newDmnTable()
      onRestore(next); bound.current = true; setBaseline(JSON.stringify(next)); setSaved(draft); setReady(true)
    }
    try { accept(await api.latestDmnDraft(id)) }
    catch (cause) {
      if (!alive.current || epoch.current !== requestEpoch) return
      if (cause instanceof ApiProblem && cause.code === DRAFT_NOT_FOUND) accept()
      else setError(cause instanceof Error ? cause.message : TEXT.failed)
    } finally { if (alive.current && epoch.current === requestEpoch) setBusy(undefined) }
  }
  const requestLoad = (id: string) => {
    if (!id || busy) return
    if (!bound.current) void load(id, true)
    else if (dirty) setPending(id)
    else void load(id)
  }
  const save = async () => {
    if (!ready || !campaignId || !canWrite || busy || conflict) return
    const requestEpoch = ++epoch.current
    const version = saved?.version ?? 0
    const payload = JSON.stringify({ campaignId, version, table })
    if (retry.current?.payload !== payload) retry.current = { payload, key: crypto.randomUUID() }
    const snapshot = fingerprint
    setBusy('save'); setError('')
    try {
      const result = await api.saveDmnDraft(campaignId, version, table, retry.current.key)
      if (!alive.current || epoch.current !== requestEpoch) return
      // 回执只更新基准版本；请求期间的新输入留在编辑器，继续显示未保存。
      setSaved(result); setBaseline(snapshot); retry.current = undefined
    } catch (cause) {
      if (!alive.current || epoch.current !== requestEpoch) return
      if (cause instanceof ApiProblem && cause.code === VERSION_CONFLICT) { setConflict(true); setError(TEXT.conflict) }
      else setError(cause instanceof Error ? cause.message : TEXT.failed)
    } finally { if (alive.current && epoch.current === requestEpoch) setBusy(undefined) }
  }

  return <Panel>
    <PanelHeader eyebrow="DRAFT VERSIONS" title="活动与草稿版本" aside={<Badge tone={saved && !dirty ? 'good' : 'warn'}>{saved && !dirty ? `已保存 · v${saved.version}` : '未保存草稿'}</Badge>} />
    <p className="dmn-help">保存保留当前规则与字段，包括尚未填写完整的内容。测试输入和计算结果不保存；草稿保存后仍需分析，尚未接入审核和发布。</p>
    {api.demoMode && <StateBanner tone="info" title="演示模式不保存版本" detail="当前允许编辑与试算；请切换真实环境后保存到数据库。" />}
    <div className="dmn-toolbar"><label>归属活动<Select aria-label="DMN 归属活动" value={campaignId} disabled={!canList || !canRead || !!busy} onChange={(event) => requestLoad(event.target.value)}><option value="" disabled>请选择有权限的活动</option>{campaigns.map((campaign) => <option key={campaign.id} value={campaign.id}>{campaign.name} · {campaign.id}</option>)}</Select></label>
      <Button disabled={!campaignId || !canRead || !!busy} onClick={() => requestLoad(campaignId)}>{busy === 'load' ? '正在加载…' : '加载最新版本'}</Button>
      <Button tone="primary" disabled={!ready || !canWrite || !!busy || conflict || (!!saved && !dirty)} onClick={() => void save()}>{busy === 'save' ? '正在保存…' : '保存草稿版本'}</Button>
    </div>
    {saved && <p className="dmn-help">基准版本 v{saved.version} · 保存人 {saved.createdBy} · 保存时间 {displayTimestamp(saved.createdAt)}{dirty ? ' · 当前修改尚未保存' : ''}</p>}
    {(!canList || !canRead || !canWrite) && <StateBanner tone="warn" title="草稿权限受限" detail={[!canList && '选择活动需要活动读取权限', !canRead && '加载需要规则读取权限', !canWrite && '保存需要规则写入权限'].filter(Boolean).join('；')} />}
    {listError && <><StateBanner tone="error" title="未能加载活动" detail={listError} /><Button onClick={() => setListAttempt((value) => value + 1)}>重试活动列表</Button></>}
    {canList && !listError && campaigns.length === 0 && <p className="dmn-help">当前没有可选择的活动；请先创建活动或确认访问范围。</p>}
    {error && <StateBanner tone="error" title={conflict ? '草稿版本冲突' : '未完成草稿操作'} detail={error} />}
    {pending !== undefined && <div role="group" aria-label="确认替换未保存草稿"><StateBanner tone="warn" title="有未保存修改" detail="加载服务端草稿会替换当前表格。请选择保留编辑，或确认放弃未保存修改后加载。" /><div className="dmn-actions"><Button onClick={() => setPending(undefined)}>保留当前编辑</Button><Button onClick={() => void load(pending)}>放弃修改并加载</Button></div></div>}
  </Panel>
}
