import { Children, Fragment, isValidElement, useState, type ReactNode, type ButtonHTMLAttributes } from 'react'
import * as Primitive from '@radix-ui/react-select'
import { Check, ChevronDown, ChevronUp } from 'lucide-react'

type Option = { value: string; label: string; disabled?: boolean }
type Props = Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'onChange' | 'value' | 'defaultValue' | 'type'> & {
  required?: boolean
  value?: string
  defaultValue?: string
  onChange?: (event: { target: { value: string } }) => void
}
const EMPTY_VALUE = '__console_empty_selection__'
function text(children: ReactNode): string {
  return Children.toArray(children).map((child) => isValidElement<{ children?: ReactNode }>(child) ? text(child.props.children) : String(child)).join('')
}
function optionsFrom(children: ReactNode): Option[] {
  return Children.toArray(children).flatMap((child) => {
    if (!isValidElement<{ children?: ReactNode; value?: string; disabled?: boolean }>(child)) return []
    if (child.type === Fragment) return optionsFrom(child.props.children)
    return [{ value: child.props.value ?? text(child.props.children), label: text(child.props.children), disabled: child.props.disabled }]
  })
}

/** 保留现有选项和值契约，统一弹层外观；键盘、触屏、类型定位和焦点由Radix处理。 */
export function Select({ children, value, defaultValue, onChange, disabled, required, name, className = '', ...triggerProps }: Props) {
  const options = optionsFrom(children)
  const [localValue, setLocalValue] = useState(defaultValue ?? options[0]?.value ?? '')
  const current = value ?? localValue
  const selected = options.find((option) => option.value === current)
  return <Primitive.Root value={current || EMPTY_VALUE} onValueChange={(next) => {
    const resolved = next === EMPTY_VALUE ? '' : next
    setLocalValue(resolved)
    onChange?.({ target: { value: resolved } })
  }} disabled={disabled} required={required} name={name}>
    <Primitive.Trigger {...triggerProps} className={`select-trigger ${className}`} title={triggerProps.title ?? selected?.label}>
      <Primitive.Value>{selected?.label ?? (current || '请选择')}</Primitive.Value>
      <Primitive.Icon asChild><ChevronDown size={15} aria-hidden="true" /></Primitive.Icon>
    </Primitive.Trigger>
    <Primitive.Portal><Primitive.Content className="select-content" position="popper" sideOffset={5} collisionPadding={12} onMouseDown={(event) => event.stopPropagation()}>
      <Primitive.ScrollUpButton className="select-scroll"><ChevronUp size={15} /></Primitive.ScrollUpButton>
      <Primitive.Viewport className="select-viewport">{options.map((option) => <Primitive.Item className="select-option" key={option.value} value={option.value || EMPTY_VALUE} data-value={option.value} disabled={option.disabled}>
        <Primitive.ItemText>{option.label}</Primitive.ItemText><Primitive.ItemIndicator><Check size={15} /></Primitive.ItemIndicator>
      </Primitive.Item>)}</Primitive.Viewport>
      <Primitive.ScrollDownButton className="select-scroll"><ChevronDown size={15} /></Primitive.ScrollDownButton>
    </Primitive.Content></Primitive.Portal>
  </Primitive.Root>
}
