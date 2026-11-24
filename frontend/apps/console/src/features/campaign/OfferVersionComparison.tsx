import { useRef, useState, type FormEvent } from 'react'
import { useInfiniteQuery, useMutation } from '@tanstack/react-query'
import { DateTimePicker } from '../../components/DateTimePicker'
import { displayTimestamp } from '../../components/dateTime'
import { RecordTable } from '../../components/RecordTable'
import { Select } from '../../components/Select'
import { Button, EmptyState, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { api } from '../../shared/api/client'
import { problemDetail } from '../../shared/api/problem'
import {
  MAX_OFFER_COMPARISON_SCENARIOS,
  type DefinitionBundle, type OfferComparison, type OfferComparisonInput,
} from '../../shared/api/schemas'
import { CNY, isOfferScopeId, minorFromYuan, offerOutcomeLabel, offerStatusLabel, yuan } from './thresholdOffer'

type Scenario = { id: number; skuId: string; shopId: string; amount: string; occurredAt: string }
const deltaYuan = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: CNY, signDisplay: 'exceptZero' })
const MAX_MINOR = 99_999_999_999_999

function starterScenarios(config: Record<string, string>): Scenario[] {
  const amount = Number(config.thresholdMinor)
  const threshold = Number.isSafeInteger(amount) && amount > 0 ? Math.min(amount, MAX_MINOR) : 0
  const amounts = threshold ? [threshold - 1, threshold, Math.min(threshold + 1, MAX_MINOR)] : [0]
  return [...new Set(amounts)].map((minor, id) => ({
    id,
    skuId: config.skuIds ?? '',
    shopId: config.shopIds ?? '',
    amount: (minor / 100).toFixed(2),
    occurredAt: config.validFrom || new Date().toISOString(),
  }))
}

export function OfferVersionComparison({ definition, config, scope }: {
  definition: DefinitionBundle; config: Record<string, string>; scope: string
}) {
  const { campaignId, definitionId, version } = definition
  const [open, setOpen] = useState(false)
  const [baselineChoice, setBaselineChoice] = useState<number>()
  const [candidateChoice, setCandidateChoice] = useState<number>()
  const [scenarios, setScenarios] = useState(() => starterScenarios(config))
  const nextScenarioId = useRef(scenarios.length)
  const history = useInfiniteQuery({
    queryKey: ['campaign-offer-versions', scope, campaignId, definitionId, version],
    initialPageParam: undefined as number | undefined,
    queryFn: ({ pageParam }) => api.offerVersions(campaignId, definitionId, pageParam),
    getNextPageParam: page => page.nextBeforeVersion ?? undefined,
    enabled: open,
    retry: false,
  })
  const versions = history.data?.pages.flatMap(page => page.items) ?? []
  const candidateVersion = candidateChoice ?? version
  const baselineVersion = baselineChoice ?? versions.find(item => item.version < version)?.version
  const targetsReady = baselineVersion !== candidateVersion
    && versions.some(item => item.version === baselineVersion)
    && versions.some(item => item.version === candidateVersion)
  const comparison = useMutation({ mutationFn: (input: OfferComparisonInput) => api.compareOffers(definitionId, input) })
  const running = comparison.isPending
  const facts = scenarios.map(({ skuId, shopId, amount, occurredAt }) => ({
    skuId: skuId.trim(), shopId: shopId.trim(), amountMinor: minorFromYuan(amount) ?? '', occurredAt,
  }))
  const ready = targetsReady && facts.every(fact => isOfferScopeId(fact.skuId) && isOfferScopeId(fact.shopId)
    && fact.amountMinor !== '' && Number.isFinite(Date.parse(fact.occurredAt)))

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!ready || running || baselineVersion === undefined) return
    comparison.mutate({ campaignId, baselineVersion, candidateVersion, scenarios: facts })
  }
  const update = (id: number, field: Exclude<keyof Scenario, 'id'>, value: string) => {
    comparison.reset()
    setScenarios(current => current.map(item => item.id === id ? { ...item, [field]: value } : item))
  }
  const add = () => {
    const last = scenarios.at(-1)
    if (!last || scenarios.length >= MAX_OFFER_COMPARISON_SCENARIOS) return
    comparison.reset()
    const id = nextScenarioId.current++
    setScenarios([...scenarios, { ...last, id }])
  }
  const remove = (id: number) => {
    comparison.reset()
    setScenarios(current => current.length < 2 ? current : current.filter(item => item.id !== id))
  }
  const options = versions.map(item => <option key={item.version} value={String(item.version)}>
    {`v${item.version} · ${offerStatusLabel[item.status] ?? item.status} · ${displayTimestamp(item.createdAt)}`}
  </option>)

  return <Panel className="offer-comparison">
    <PanelHeader title="满减版本对比" aside={<Button aria-controls="offer-version-comparison" aria-expanded={open}
      onClick={() => setOpen(value => !value)}>{open ? '收起对比' : '展开对比'}</Button>} />
    <div id="offer-version-comparison" hidden={!open}>
      {history.isPending && <StateBanner tone="loading" title="正在读取历史版本" detail="从当前活动的 Offer 读取已保存版本。" />}
      {history.isError && !history.isFetchNextPageError && <StateBanner tone="error" title="版本读取失败"
        detail={problemDetail(history.error)} action={<Button onClick={() => void history.refetch()}>重试</Button>} />}
      {history.isSuccess && versions.length < 2 && !history.hasNextPage && <EmptyState
        title="至少需要两个已保存版本" detail="在 Offer 设计器中修改并保存后，回到这里对比。" />}
      {history.hasNextPage && <div className="workbench-actions">
        <Button disabled={running || history.isFetching} onClick={() => void history.fetchNextPage()}>{history.isFetchingNextPage ? '正在加载版本…' : history.isFetchNextPageError ? '重试加载旧版本' : '加载更早版本'}</Button>
        {history.isFetchNextPageError && <span className="field-hint" role="alert">{problemDetail(history.error)}</span>}
      </div>}
      {versions.length >= 2 && <form className="offer-comparison-form" onSubmit={submit}>
        <div className="form-grid">
          <label className="field"><span>基准版本</span><Select aria-label="基准版本" value={String(baselineVersion ?? '')} disabled={running}
            onChange={event => { comparison.reset(); setBaselineChoice(Number(event.target.value)) }}>{options}</Select></label>
          <label className="field"><span>候选版本</span><Select aria-label="候选版本" value={String(candidateVersion)} disabled={running}
            onChange={event => { comparison.reset(); setCandidateChoice(Number(event.target.value)) }}>{options}</Select></label>
        </div>
        <p className="field-hint">两版使用相同的单商品人民币订单。预填金额按最新版本的门槛生成，可编辑。预算、库存和其他活动叠加需独立核验。</p>
        {scenarios.map((scenario, index) => <fieldset key={scenario.id} className="offer-comparison-scenario" disabled={running}>
          <legend>场景 {index + 1}</legend>
          <div className="form-grid offer-scenario-fields">
            <label className="field"><span>商品 SKU ID</span><input required maxLength={128} value={scenario.skuId}
              onChange={event => update(scenario.id, 'skuId', event.target.value)} /></label>
            <label className="field"><span>门店 ID</span><input required maxLength={128} value={scenario.shopId}
              onChange={event => update(scenario.id, 'shopId', event.target.value)} /></label>
            <label className="field"><span>订单金额（元）</span><input required inputMode="decimal" value={scenario.amount}
              onChange={event => update(scenario.id, 'amount', event.target.value)} /></label>
            <div className="field"><span>订单时间</span><DateTimePicker label={`场景 ${index + 1} 订单时间`} disabled={running}
              value={scenario.occurredAt} onChange={value => update(scenario.id, 'occurredAt', value)} /></div>
          </div>
          {scenarios.length > 1 && <Button tone="ghost" aria-label={`移除场景 ${index + 1}`} onClick={() => remove(scenario.id)}>移除场景</Button>}
        </fieldset>)}
        {baselineVersion === candidateVersion && <p className="field-hint" role="status">请选择两个不同的已保存版本。</p>}
        {targetsReady && !ready && <p className="field-hint" role="status">请补全商品、门店、订单时间与金额。金额可填到分。</p>}
        <div className="workbench-actions">
          <Button disabled={running || scenarios.length >= MAX_OFFER_COMPARISON_SCENARIOS} onClick={add}>添加场景</Button>
          <Button tone="primary" type="submit" disabled={!ready || running}>{running ? '正在对比…' : '运行版本对比'}</Button>
          <span className="field-hint">已配置 {scenarios.length} 个场景，最多 {MAX_OFFER_COMPARISON_SCENARIOS} 个。</span>
        </div>
      </form>}
      {comparison.isError && <StateBanner tone="error" title="版本对比失败" detail={problemDetail(comparison.error)} />}
      {comparison.data && comparison.variables && <ComparisonResults result={comparison.data} scenarios={comparison.variables.scenarios} />}
    </div>
  </Panel>
}

function ComparisonResults({ result, scenarios }: { result: OfferComparison; scenarios: OfferComparisonInput['scenarios'] }) {
  const before = `v${result.baselineVersion}`
  const after = `v${result.candidateVersion}`
  const changed = result.rows.filter(row => row.baseline.payableMinor !== row.candidate.payableMinor).length
  const columns = [
    { label: '场景', width: 80 }, { label: '订单金额', width: 104 }, { label: '应付差额', width: 104 },
    { label: `基准 ${before} 命中`, width: 146 }, { label: `${before} 优惠`, width: 100 }, { label: `${before} 应付`, width: 102 },
    { label: `候选 ${after} 命中`, width: 146 }, { label: `${after} 优惠`, width: 100 }, { label: `${after} 应付`, width: 102 },
  ]
  return <div className="offer-comparison-results">
    <p role="status">{result.rows.length} 个场景完成对比，{changed} 个场景的应付金额有变化。应付差额为候选减基准。</p>
    <p className="field-hint offer-comparison-scroll-hint">横向滚动查看两版明细。</p>
    <RecordTable label="满减版本对比结果" columns={columns}>{result.rows.map((row, index) => <tr key={index}>
      <td>场景 {index + 1}</td><td>{yuan.format(Number(scenarios[index].amountMinor) / 100)}</td>
      <td><strong>{deltaYuan.format((row.candidate.payableMinor - row.baseline.payableMinor) / 100)}</strong></td>
      <td>{offerOutcomeLabel[row.baseline.outcome]}</td>
      <td>{yuan.format(row.baseline.discountMinor / 100)}</td><td>{yuan.format(row.baseline.payableMinor / 100)}</td>
      <td>{offerOutcomeLabel[row.candidate.outcome]}</td>
      <td>{yuan.format(row.candidate.discountMinor / 100)}</td><td>{yuan.format(row.candidate.payableMinor / 100)}</td>
    </tr>)}</RecordTable>
  </div>
}
