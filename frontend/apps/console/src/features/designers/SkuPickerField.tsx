import { Badge, EmptyState, StateBanner } from '../../components/ui'
import type { BenefitSku } from '../../shared/api/schemas'

const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日']

function money(minor: number | null | undefined, currency: string | null | undefined) {
  if (minor == null) return '—'
  const amount = (minor / 100).toFixed(2)
  return `${currency || 'CNY'} ${amount}`
}

function formatInstant(value: string | null | undefined) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN')
}

function validityLabel(sku: BenefitSku) {
  if (sku.validityType === 'RELATIVE') {
    return `领取后 ${sku.relativeDays ?? '—'} 天`
  }
  return `${formatInstant(sku.validFrom)} ~ ${formatInstant(sku.validTo)}`
}

export function TemplateSummary({ sku }: { sku: BenefitSku }) {
  const weekdays = sku.usableWeekdays.length > 0
    ? sku.usableWeekdays.map((day) => WEEKDAYS[day - 1] ?? String(day)).join('、')
    : '不限'
  return (
    <div className="template-summary" data-testid="template-summary">
      <div>
        <span>模板状态</span>
        <strong><Badge tone={sku.status === 'ACTIVE' ? 'good' : 'warn'}>{sku.status}</Badge> · v{sku.version}</strong>
      </div>
      <div>
        <span>有效期</span>
        <strong>{validityLabel(sku)}</strong>
      </div>
      <div>
        <span>面额</span>
        <strong>{money(sku.faceValueMinor, sku.currency)}</strong>
      </div>
      <div>
        <span>限额</span>
        <strong>日配额 {sku.dailyQuota ?? '—'} · 用户日 {sku.userLimitPerDay ?? '—'} · 用户总 {sku.userLimitTotal ?? '—'}</strong>
      </div>
      <div className="span-2">
        <span>可用星期</span>
        <strong>周{weekdays}</strong>
      </div>
    </div>
  )
}

export function SkuPickerField({
  value,
  onChange,
  disabled,
  skus,
  loading,
  error,
}: {
  value: string
  onChange: (skuId: string) => void
  disabled?: boolean
  skus: BenefitSku[]
  loading?: boolean
  error?: string
}) {
  const selected = skus.find((item) => item.skuId === value)
  const orphan = Boolean(value) && !selected
  if (loading) {
    return <div className="sku-bind" aria-busy="true"><div className="skeleton-list" data-testid="sku-skeleton"><i /></div></div>
  }
  if (error) {
    return <div className="sku-bind"><StateBanner tone="error" title="SKU 目录加载失败" detail={error} /></div>
  }
  if (skus.length === 0 && !value) {
    return (
      <div className="sku-bind">
        <EmptyState title="本货主尚无已投放模板" detail="请用同一业务租户在权益中台创建并上线。其他租户的商品不会出现在这里。草稿可以先不绑。" />
      </div>
    )
  }
  return (
    <div className="sku-bind">
      <label className="field">
        <span>已投放 SKU</span>
        <select aria-label="已投放 SKU" value={value} disabled={disabled} onChange={(event) => onChange(event.target.value)}>
          <option value="">不绑定（草稿可空）</option>
          {orphan && <option value={value}>{value}（当前绑定，已不在已投放列表）</option>}
          {skus.map((item) => (
            <option key={item.skuId} value={item.skuId}>
              {item.skuId} · {item.benefitType} · {money(item.faceValueMinor, item.currency)}
            </option>
          ))}
        </select>
      </label>
      {orphan && <StateBanner tone="warn" title="已绑定模板不在已投放列表" detail={`${value} 可能已暂停或下线。发布 ACTIVE 会被服务端拒绝。`} />}
      {selected && <TemplateSummary sku={selected} />}
    </div>
  )
}

export { validityLabel, money as skuFaceValue }
