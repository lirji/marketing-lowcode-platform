import { useState } from 'react'
import * as Popover from '@radix-ui/react-popover'
import { DayPicker } from 'react-day-picker'
import { zhCN } from 'react-day-picker/locale'
import { CalendarDays, Info } from 'lucide-react'
import { Select } from './Select'
import { displayInstant, instantParts, preserveInstantPrecision, toInstant } from './dateTime'
import 'react-day-picker/style.css'

type Props = { label: string; value: string; onChange: (value: string) => void; disabled?: boolean; invalid?: boolean; describedBy?: string }
const HOURS = Array.from({ length: 24 }, (_, index) => String(index).padStart(2, '0'))
const MINUTES = Array.from({ length: 60 }, (_, index) => String(index).padStart(2, '0'))

/** 弹层内编辑临时值，确认才更新业务草稿；取消/Escape不改变已有时间。 */
export function DateTimePicker({ label, value, onChange, disabled, invalid, describedBy }: Props) {
  const [open, setOpen] = useState(false)
  const [day, setDay] = useState<Date>()
  const [hour, setHour] = useState('00')
  const [minute, setMinute] = useState('00')
  const [second, setSecond] = useState('00')
  const [millisecond, setMillisecond] = useState(0)
  const [month, setMonth] = useState<Date>()
  const changeOpen = (next: boolean) => {
    if (next) {
      const parts = instantParts(value)
      setDay(parts?.day); setMonth(parts?.day)
      setHour(parts?.hour ?? '00'); setMinute(parts?.minute ?? '00'); setSecond(parts?.second ?? '00'); setMillisecond(parts?.millisecond ?? 0)
    }
    setOpen(next)
  }
  return <Popover.Root open={open} onOpenChange={changeOpen} modal>
    <Popover.Trigger asChild><button type="button" className="date-time-trigger" aria-label={label} aria-invalid={invalid} aria-describedby={describedBy} disabled={disabled}><CalendarDays size={16} /><span>{displayInstant(value)}</span><small>北京时间</small></button></Popover.Trigger>
    <Popover.Portal><Popover.Content className="date-time-content" aria-label={`选择${label}`} sideOffset={6} collisionPadding={10} onMouseDown={(event) => event.stopPropagation()}>
      <header><strong>{label}</strong><span>北京时间 UTC+08:00</span></header>
      <DayPicker mode="single" locale={zhCN} selected={day} onSelect={setDay} month={month} onMonthChange={setMonth} defaultMonth={day} autoFocus />
      <div className="time-parts">{[{ label: '时', values: HOURS, value: hour, set: setHour }, { label: '分', values: MINUTES, value: minute, set: setMinute }, { label: '秒', values: MINUTES, value: second, set: setSecond }].map((part) => <label key={part.label}><span>{part.label}</span><Select aria-label={`${label}·${part.label}`} value={part.value} onChange={(event) => part.set(event.target.value)}>{part.values.map((item) => <option key={item}>{item}</option>)}</Select></label>)}</div>
      <p className="field-hint"><Info size={13} />选择日期及时间后确认，取消会保留原值。</p>
      <footer><button className="button" type="button" onClick={() => { onChange(''); setOpen(false) }}>清空</button><span /><button className="button" type="button" onClick={() => setOpen(false)}>取消</button><button className="button primary" type="button" disabled={!day} onClick={() => { if (day) onChange(preserveInstantPrecision(toInstant(day, hour, minute, second, millisecond), value)); setOpen(false) }}>确认时间</button></footer>
    </Popover.Content></Popover.Portal>
  </Popover.Root>
}
