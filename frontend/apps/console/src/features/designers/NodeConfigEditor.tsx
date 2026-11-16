import { useState } from 'react'
import { DateTimePicker } from '../../components/DateTimePicker'
import { FieldHint } from '../../components/FieldHint'
import { Select } from '../../components/Select'
import { Button } from '../../components/ui'
import type { BenefitView } from '../../shared/api/schemas'
import { nodeFields, supportedNode, NODE_FIELD_KIND, type NodeField } from './nodeConfig'
import type { DesignerNode } from './designerTypes'

/** 使用已核对的节点契约，不把通用 field/value 表单套在金额、时长和副作用上。 */
export function NodeConfigEditor({ node, onChange, disabled, benefits = [], benefitError }: {
  node: DesignerNode; onChange: (name: string, value: string) => void; disabled: boolean
  benefits?: BenefitView[]; benefitError?: string
}) {
  const type = node.data.stableTypeId ?? ''
  const fields = nodeFields(type)
  const [newKey, setNewKey] = useState('')
  const [newValue, setNewValue] = useState('')
  const extras = Object.entries(node.data.config).filter(([key]) => !fields.some((field) => field.key === key) && key !== 'basisPoints')
  const basicOfferKeys = new Set(['amountMinor', 'discountBasisPoints', 'validFrom', 'validTo', 'benefitDefinitionVersion', 'thresholdMinor', 'capMinor'])
  const advanced = (field: NodeField) => type.startsWith('offer.') && !basicOfferKeys.has(field.key)
  const renderField = (field: NodeField) => {
      const value = node.data.config[field.key] ?? (field.key === 'discountBasisPoints' ? node.data.config.basisPoints : undefined) ?? field.defaultValue ?? ''
      const hintId = `${node.id}-${field.key}-hint`
      return <div className="field" key={field.key}><label htmlFor={`${node.id}-${field.key}`}>{field.label}{field.required ? ' *' : ''}</label>
        {field.kind === NODE_FIELD_KIND.TIME ? <DateTimePicker label={field.label} value={value} onChange={(next) => onChange(field.key, next)} disabled={disabled} describedBy={hintId} />
          : field.kind === NODE_FIELD_KIND.CHOICE ? <Select id={`${node.id}-${field.key}`} aria-label={field.label} aria-describedby={hintId} value={value} onChange={(event) => onChange(field.key, event.target.value)}>{field.defaultValue === '' && <option value="">请选择</option>}{field.options?.map((option) => <option key={option}>{option}</option>)}</Select>
          : field.kind === NODE_FIELD_KIND.BENEFIT ? <><Select id={`${node.id}-${field.key}`} aria-label={field.label} aria-describedby={hintId} value={value} onChange={(event) => onChange(field.key, event.target.value)}><option value="">请选择权益版本</option>{value && !benefits.some((item) => `${item.benefitId}@${item.version}` === value) && <option value={value}>已绑定：{value}（当前目录未返回）</option>}{benefits.map((item) => <option key={`${item.benefitId}@${item.version}`} value={`${item.benefitId}@${item.version}`}>{item.name} · {item.benefitId}@{item.version} · {item.status}</option>)}</Select>{benefitError && <small role="alert">权益目录加载失败：{benefitError}</small>}{!benefitError && benefits.length === 0 && <small>暂无可选权益版本，请先在权益设计器中登记。</small>}</>
          : <input id={`${node.id}-${field.key}`} aria-describedby={hintId} type={field.kind === NODE_FIELD_KIND.INTEGER ? 'number' : 'text'} step={field.kind === NODE_FIELD_KIND.INTEGER ? 1 : undefined} min={field.min} max={field.max} value={value} onChange={(event) => onChange(field.key, event.target.value)} />}
        <FieldHint id={hintId}>{field.hint}</FieldHint>
      </div>
  }
  return <fieldset className="node-config-fields" disabled={disabled}>
    {!supportedNode(type) && <p className="field-hint">该节点暂不支持可视化配置，已有参数保留；请先处理控制面兼容性校验。</p>}
    {type === 'journey.grant' && <p className="grant-intent-note">当前执行资源账户扣减及发放台账，尚未接入外部 AwardIntent 履约，不能据此认定外部权益已到账。</p>}
    {fields.length === 0 && supportedNode(type) && <p className="field-hint">此节点没有配置参数，请在下方设置出口连接。</p>}
    {fields.filter((field) => !advanced(field)).map(renderField)}
    {fields.some(advanced) && <details><summary>叠加、资金与适用范围{fields.some((field) => advanced(field) && node.data.config[field.key]) ? '（已有配置）' : '（可选）'}</summary>{fields.filter(advanced).map(renderField)}</details>}
    {extras.length > 0 && <details><summary>其他已保存参数（{extras.length}）</summary>{extras.map(([key, value]) => <label className="field" key={key}><span>{key}</span><input value={value} onChange={(event) => onChange(key, event.target.value)} /></label>)}</details>}
    {(type === 'journey.send' || type === 'journey.webhook') && <div className="node-extra-fields"><strong>{type === 'journey.send' ? '添加模板变量' : '添加动作参数'}</strong><label className="field"><span>参数名</span><input value={newKey} onChange={(event) => setNewKey(event.target.value)} /></label><label className="field"><span>参数值</span><input value={newValue} onChange={(event) => setNewValue(event.target.value)} /></label><Button disabled={disabled || !newKey.trim() || !newValue || fields.some((field) => field.key === newKey.trim())} onClick={() => { onChange(newKey.trim(), newValue); setNewKey(''); setNewValue('') }}>添加参数</Button><p className="field-hint">参数以字符串传给模板或动作，不填写敏感原文；必需变量以已批准模板为准。</p></div>}
  </fieldset>
}
