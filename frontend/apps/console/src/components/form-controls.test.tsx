import { useState } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Select } from './Select'
import { DateTimePicker } from './DateTimePicker'
import { displayInstant, instantParts, preserveInstantPrecision, toInstant } from './dateTime'
import { Modal } from './ui'

describe('business time conversion', () => {
  it('converts across year boundaries without depending on machine timezone', () => {
    const parts = instantParts('2026-12-31T20:15:20.123Z')!
    expect([parts.day.getFullYear(), parts.day.getMonth(), parts.day.getDate(), parts.hour]).toEqual([2027, 0, 1, '04'])
    expect(toInstant(parts.day, parts.hour, parts.minute, parts.second, parts.millisecond)).toBe('2026-12-31T20:15:20.123Z')
    expect(toInstant(new Date(2026, 0, 1), '00', '00', '00')).toBe('2025-12-31T16:00:00.000Z')
    expect(displayInstant('2026-01-01T00:00:00Z')).toContain('08:00:00')
    expect(instantParts('invalid')).toBeUndefined()
    expect(preserveInstantPrecision('2026-01-01T00:00:00.123Z', '2026-01-01T00:00:00.123456789Z')).toBe('2026-01-01T00:00:00.123456789Z')
    expect(preserveInstantPrecision('2026-01-02T01:00:00.123Z', '2026-01-01T00:00:00.123456789Z')).toBe('2026-01-02T01:00:00.123456789Z')
  })
})

describe('form controls', () => {
  it('supports keyboard selection, empty value and disabled options inside a modal', async () => {
    const close = vi.fn()
    function Form() {
      const [value, setValue] = useState('')
      return <Modal title="编辑" onClose={close}><Select aria-label="组织" value={value} onChange={(event) => setValue(event.target.value)}><option value="">请选择</option><option value="blocked" disabled>不可用</option><option value="north">华北</option></Select></Modal>
    }
    const user = userEvent.setup()
    render(<Form />)
    const trigger = screen.getByRole('combobox', { name: '组织' })
    trigger.focus()
    await user.keyboard('{ArrowDown}{End}{Enter}')
    expect(trigger).toHaveTextContent('华北')
    await user.click(trigger)
    await user.keyboard('{Escape}')
    expect(trigger).toHaveFocus()
    expect(close).not.toHaveBeenCalled()
    await user.click(trigger)
    await user.click(screen.getByRole('option', { name: '请选择' }))
    expect(trigger).toHaveTextContent('请选择')
    expect(close).not.toHaveBeenCalled()
  })

  it('keeps the original instant on cancel and confirms a selected date without raw text entry', async () => {
    const change = vi.fn()
    const user = userEvent.setup()
    render(<DateTimePicker label="开始时间" value="2026-10-01T00:00:00Z" onChange={change} />)
    const trigger = screen.getByRole('button', { name: '开始时间' })
    await user.click(trigger)
    expect(screen.getByRole('dialog')).toHaveTextContent('北京时间 UTC+08:00')
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '取消' }))
    expect(change).not.toHaveBeenCalled()
    expect(trigger).toHaveFocus()
    await user.click(trigger)
    await user.click(screen.getByRole('button', { name: '确认时间' }))
    expect(change).toHaveBeenCalledWith('2026-10-01T00:00:00Z')
  })

  it('requires a date before confirmation and clears only explicitly', async () => {
    const change = vi.fn()
    const user = userEvent.setup()
    render(<DateTimePicker label="结束时间" value="" onChange={change} />)
    await user.click(screen.getByRole('button', { name: '结束时间' }))
    expect(screen.getByRole('button', { name: '确认时间' })).toBeDisabled()
    await user.keyboard('{Escape}')
    expect(change).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '结束时间' }))
    await user.click(screen.getByRole('button', { name: '清空' }))
    expect(change).toHaveBeenCalledWith('')
  })
})
