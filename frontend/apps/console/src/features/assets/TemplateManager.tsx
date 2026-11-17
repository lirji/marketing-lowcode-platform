import { useCallback, useEffect, useRef, useState } from 'react'
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { Badge, Button, EmptyState, Modal, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { RecordTable } from '../../components/RecordTable'
import { ListToolbar } from '../../components/ListToolbar'
import { FieldHint } from '../../components/FieldHint'
import { displayInstant } from '../../components/dateTime'
import { api } from '../../shared/api/client'
import { problemDetail } from '../../shared/api/problem'
import { TEMPLATE_PAGE_SIZES, type TemplateView } from '../../shared/api/schemas'
import { useAuth } from '../../shared/auth/useAuth'
import { TEMPLATE_STATE, templateForm, templatePayload, templateValidation, type TemplateForm } from './templateForm'

const SEARCH_DEBOUNCE_MS = 300
const identityKey = (auth: ReturnType<typeof useAuth>) => JSON.stringify([auth.tenantId, auth.subject, auth.organizations, auth.shops, auth.permissions])
const templateTime = (value?: string) => value ? displayInstant(value) : '时间未知'
const stateLabel = (state?: string) => state === TEMPLATE_STATE.APPROVED ? '已批准' : state ?? '状态未知'

/** 只管理模板版本；保存不会发送消息，也不会替换旅程引用的旧版本。 */
export function TemplateManager() {
  const auth = useAuth()
  if (!auth.hasPermission('template:read')) return <StateBanner tone="warn" title="没有查看模板的权限" detail="需要 template:read 权限。" />
  if (api.demoMode) return <Panel><PanelHeader eyebrow="ENGAGEMENT TEMPLATES" title="渠道模板" /><Button tone="primary" disabled>新建消息模板</Button><EmptyState title="模板列表需要连接服务" detail="静态预览不读取或保存消息模板；连接后可查看实际版本与正文。" /></Panel>
  return <TemplateWorkspace key={identityKey(auth)} />
}

function TemplateWorkspace() {
  const auth = useAuth(), queryClient = useQueryClient()
  const [search, setSearch] = useState(''), [selected, setSelected] = useState<TemplateView | null>(null)
  const normalizedSearch = search.trim(), [confirmedSearch, setConfirmedSearch] = useState('')
  useEffect(() => {
    const timer = window.setTimeout(() => setConfirmedSearch(normalizedSearch), SEARCH_DEBOUNCE_MS)
    return () => window.clearTimeout(timer)
  }, [normalizedSearch])
  const searchPending = normalizedSearch !== confirmedSearch
  const query = useInfiniteQuery({
    queryKey: ['templates', 'pages', identityKey(auth), confirmedSearch],
    queryFn: ({ pageParam }) => api.templatePage({ q: confirmedSearch, limit: TEMPLATE_PAGE_SIZES.DEFAULT, cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: last => last.hasMore ? last.nextCursor ?? undefined : undefined,
    enabled: !api.demoMode && auth.hasPermission('template:read'),
  })
  const [editor, setEditor] = useState<{ source?: TemplateView } | null>(null), [saved, setSaved] = useState<TemplateView | null>(null)
  const canWrite = !api.demoMode && auth.hasPermission('template:write')
  const rows = searchPending ? [] : query.data?.pages.flatMap(page => page.items) ?? []
  const initialError = !searchPending && query.isError && rows.length === 0
  return <Panel><PanelHeader eyebrow="ENGAGEMENT TEMPLATES" title="渠道模板" />
    <p className="muted">列表展示各模板的最新版本。新版本按现有协议保存为已批准状态；历史版本及旅程引用保持原值，保存不会发送消息。</p>
    <div className="toolbar"><Button tone="primary" disabled={!canWrite} onClick={() => { setSaved(null); setEditor({}) }}>新建消息模板</Button><Button disabled={api.demoMode || query.isFetching || searchPending} onClick={() => void query.refetch()}>刷新模板</Button></div>
    {!canWrite && <p className="muted">{api.demoMode ? '演示模式不保存模板，请连接真实服务。' : '需要 template:write 权限才能保存新版本。'}</p>}
    {saved && <StateBanner tone="success" title="模板版本已保存" detail={`${saved.templateId} v${saved.version} · ${stateLabel(saved.state)}；未发送消息，现有引用不会自动切换。`} />}
    {initialError && <><StateBanner tone="error" title="模板加载失败" detail={problemDetail(query.error)} /><Button onClick={() => void query.refetch()}>重试模板查询</Button></>}
    <ListToolbar query={search} onQueryChange={setSearch} searchLabel="搜索消息模板" describedBy="template-search-hint" placeholder="搜索模板 ID、版本、渠道、变量、状态或时间" count={!searchPending && query.data ? rows.length : undefined} unit="项（已加载）" onReset={search ? () => setSearch('') : undefined} />
    <FieldHint id="template-search-hint">搜索当前租户全部模板的 ID、版本、渠道、必需变量、状态及北京时间创建时间，按关键词包含匹配；正文在详情中完整查看。</FieldHint>
    {(query.isPending || searchPending) && !api.demoMode ? <div className="skeleton-list" aria-label="正在加载模板"><i /><i /></div> : initialError ? null : rows.length === 0 ? <EmptyState title="没有匹配的消息模板" detail={search ? '清除或调整关键词后再试。' : '可通过新建消息模板保存第一个版本。'} /> : <RecordTable label="消息模板" columns={['模板 ID', '版本', '渠道', '必需变量', '状态', '创建时间（北京时间）', '操作']} actions>
      {rows.map((item) => <tr key={item.templateId}><td>{item.templateId}</td><td>v{item.version}</td><td>{item.channel}</td><td>{item.requiredVariables.join(', ') || '无'}</td><td><Badge tone={item.state === TEMPLATE_STATE.APPROVED ? 'good' : 'neutral'}>{stateLabel(item.state)}</Badge></td><td>{templateTime(item.createdAt)}</td><td><Button aria-label={`查看模板 ${item.templateId}`} onClick={() => setSelected(item)}>查看详情</Button><Button aria-label={`编辑模板 ${item.templateId} 新版本`} disabled={!canWrite || item.content == null || item.version >= Number.MAX_SAFE_INTEGER} onClick={() => { setSaved(null); setEditor({ source: item }) }}>编辑新版本</Button></td></tr>)}
    </RecordTable>}
    {!searchPending && query.isFetchNextPageError && <StateBanner tone="error" title="下一页模板加载失败" detail={problemDetail(query.error)} />}
    {!searchPending && query.isRefetchError && rows.length > 0 && <StateBanner tone="error" title="模板刷新失败" detail="当前保留上次结果，请重试刷新。" />}
    {!api.demoMode && !searchPending && query.hasNextPage && <Button onClick={() => void query.fetchNextPage()} disabled={query.isFetching}>{query.isFetchingNextPage ? '正在加载更多模板…' : query.isFetchNextPageError ? '重试下一页模板' : '加载更多模板'}</Button>}
    {!api.demoMode && !searchPending && !query.isPending && !initialError && <p className="field-hint">已加载 {rows.length} 项；按模板 ID 升序，仅展示每个模板的最新版本。{query.hasNextPage ? '还有更多结果。' : '当前搜索已全部加载。'} 新增或更新后可刷新列表。</p>}
    {selected && <Modal title={selected.templateId} description={`消息模板 v${selected.version} · ${stateLabel(selected.state)}`} onClose={() => setSelected(null)}>
      <div className="approval-detail"><dl className="detail-facts"><div><dt>渠道</dt><dd>{selected.channel}</dd></div><div><dt>必需变量</dt><dd>{selected.requiredVariables.join(', ') || '无'}</dd></div><div><dt>创建时间（北京时间）</dt><dd>{templateTime(selected.createdAt)}</dd></div></dl>
      <h3>模板正文</h3><p className="template-content">{selected.content ?? '当前服务未返回正文，不能据此编辑。'}</p>
      <footer className="modal-actions"><Button onClick={() => setSelected(null)}>关闭详情</Button></footer></div>
    </Modal>}
    {editor && <TemplateEditor source={editor.source} onClose={() => setEditor(null)} onSaved={(value) => { setSaved(value); setEditor(null); void queryClient.invalidateQueries({ queryKey: ['templates'] }) }} />}
  </Panel>
}

function TemplateEditor({ source, onClose, onSaved }: { source?: TemplateView; onClose: () => void; onSaved: (value: TemplateView) => void }) {
  const [form, setForm] = useState<TemplateForm>(() => templateForm(source)), [pending, setPending] = useState(false)
  const [error, setError] = useState<unknown>(null), [confirmClose, setConfirmClose] = useState(false)
  const baseline = useRef(JSON.stringify(templateForm(source))), active = useRef(true), inFlight = useRef(false)
  const command = useRef<{ fingerprint: string; key: string } | null>(null)
  const dirty = JSON.stringify(form) !== baseline.current
  const closeState = useRef({ pending, dirty, onClose }); closeState.current = { pending, dirty, onClose }
  const requestClose = useCallback(() => {
    if (closeState.current.pending) return
    if (closeState.current.dirty) setConfirmClose(true)
    else closeState.current.onClose()
  }, [])
  useEffect(() => { active.current = true; return () => { active.current = false } }, [])
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => { if (dirty || pending) event.preventDefault() }
    window.addEventListener('beforeunload', warn); return () => window.removeEventListener('beforeunload', warn)
  }, [dirty, pending])
  const invalid = templateValidation(form, source?.version)
  const patch = (key: keyof TemplateForm, value: string) => { setForm((current) => ({ ...current, [key]: value })); setError(null); setConfirmClose(false) }
  const save = async () => {
    if (invalid || inFlight.current) return
    const payload = templatePayload(form), fingerprint = JSON.stringify(payload)
    // 超时/断网后保持同载荷同键；操作者修改内容时生成新键，避免重复写入或错误回放。
    if (command.current?.fingerprint !== fingerprint) command.current = { fingerprint, key: crypto.randomUUID() }
    inFlight.current = true; setPending(true); setError(null)
    try { const value = await api.createTemplate(payload, command.current.key); if (active.current) onSaved(value) }
    catch (failure) { if (active.current) setError(failure) }
    finally { inFlight.current = false; if (active.current) setPending(false) }
  }
  return <Modal title={source ? `编辑 ${source.templateId} 新版本` : '新建消息模板'} description="保存后为已批准状态；仅追加版本，不发送消息或更改现有引用。" onClose={requestClose}>
    <div className="approval-detail">
    {error != null && <StateBanner tone="error" title="模板保存失败" detail={problemDetail(error)} />}
    {pending && <StateBanner tone="loading" title="正在保存模板版本" detail="请等待结果。若连接中断，可保留原内容重试；离开不代表服务器取消保存。" />}
    {confirmClose && <div role="alert"><p>当前修改尚未保存。关闭后将丢失这些输入。</p><div className="toolbar"><Button onClick={() => setConfirmClose(false)}>继续编辑</Button><Button onClick={onClose}>放弃修改并关闭</Button></div></div>}
    <fieldset disabled={pending} className="template-fields">
      <label className="field"><span>模板 ID</span><input aria-label="模板 ID" aria-describedby="template-id-hint" value={form.templateId} readOnly={Boolean(source)} maxLength={128} onChange={(event) => patch('templateId', event.target.value)} /><FieldHint id="template-id-hint">唯一引用标识，最长128字符，不含空白；编辑现有模板时保持此 ID。</FieldHint></label>
      <label className="field"><span>目标版本</span><input aria-label="目标版本" aria-describedby="template-version-hint" type="number" min={source ? source.version + 1 : 1} max={Number.MAX_SAFE_INTEGER} step="1" value={form.version} onChange={(event) => patch('version', event.target.value)} /><FieldHint id="template-version-hint">{source ? `当前读取为 v${source.version}；保存更高的未占用整数版本。` : '新模板从1开始；重复版本会被拒绝，不能覆盖。'}</FieldHint></label>
      <label className="field"><span>渠道标识</span><input aria-label="渠道标识" aria-describedby="template-channel-hint" value={form.channel} maxLength={32} onChange={(event) => patch('channel', event.target.value)} /><FieldHint id="template-channel-hint">使用已配置的渠道标识，例如 SMS、EMAIL 或 PUSH；保存不验证外部通道已接通。</FieldHint></label>
      <label className="field"><span>模板正文</span><textarea aria-label="模板正文" aria-describedby="template-content-hint" rows={7} maxLength={8000} value={form.content} onChange={(event) => patch('content', event.target.value)} /><FieldHint id="template-content-hint">最长8000字符，按原文保存。变量用 {'{{name}}'} 引用；EMAIL必须包含 unsubscribe 退订说明。</FieldHint></label>
      <label className="field"><span>必需变量</span><textarea aria-label="必需变量" aria-describedby="template-variables-hint" rows={3} value={form.variables} onChange={(event) => patch('variables', event.target.value)} /><FieldHint id="template-variables-hint">可留空；每行一个，最多32个。变量以字母或下划线开头，之后可用字母、数字、下划线、点和短横线，每个最长60字符。</FieldHint></label>
    </fieldset>
    {invalid && <p className="muted" role="status">{invalid}</p>}
    <footer className="modal-actions"><Button disabled={pending} onClick={requestClose}>取消编辑</Button><Button tone="primary" disabled={pending || Boolean(invalid)} onClick={() => void save()}>{pending ? '保存中…' : '保存新版本'}</Button></footer></div>
  </Modal>
}
