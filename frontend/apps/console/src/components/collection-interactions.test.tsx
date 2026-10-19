import { useState } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { ActionMenu } from './ActionMenu'
import { PageTabs } from './PageTabs'

function Location() {
  const location = useLocation()
  return <output>{location.pathname}{location.search}</output>
}

describe('collection controls', () => {
  it('supports menu keyboard navigation, Escape focus return and real route state', async () => {
    const user = userEvent.setup()
    render(<MemoryRouter><ActionMenu label="设计" subject="活动甲" items={[{ label: '规则', to: '/offer?campaignId=A' }, { label: '旅程', to: '/journey?campaignId=A' }]} /><Location /></MemoryRouter>)
    const trigger = screen.getByRole('button', { name: '设计：活动甲' })
    trigger.focus()
    await user.keyboard('{ArrowDown}')
    expect(screen.getByRole('menuitem', { name: '规则' })).toHaveFocus()
    await user.keyboard('{ArrowDown}')
    expect(screen.getByRole('menuitem', { name: '旅程' })).toHaveFocus()
    await user.keyboard('{Escape}')
    expect(trigger).toHaveFocus()
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    await user.click(trigger)
    await user.click(screen.getByRole('menuitem', { name: '旅程' }))
    expect(screen.getByText('/journey?campaignId=A')).toBeInTheDocument()
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('dismisses the row menu when clicking outside', async () => {
    const user = userEvent.setup()
    render(<MemoryRouter><ActionMenu label="设计" subject="活动甲" items={[{ label: '规则', to: '/offer' }]} /><button>其他操作</button></MemoryRouter>)
    await user.click(screen.getByRole('button', { name: '设计：活动甲' }))
    await user.click(screen.getByRole('button', { name: '其他操作' }))
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '其他操作' })).toHaveFocus()
  })

  it('uses arrow and Home/End keys to select and label the tab panel', async () => {
    function Example() {
      const [selected, setSelected] = useState('audience')
      return <PageTabs label="资产分类" items={[{ value: 'audience', label: '人群' }, { value: 'benefit', label: '权益' }, { value: 'template', label: '模板' }]} value={selected} onChange={setSelected}>{selected}</PageTabs>
    }
    const user = userEvent.setup()
    render(<Example />)
    screen.getByRole('tab', { name: '人群' }).focus()
    await user.keyboard('{ArrowRight}')
    expect(screen.getByRole('tab', { name: '权益' })).toHaveFocus()
    expect(screen.getByRole('tabpanel', { name: '权益' })).toHaveTextContent('benefit')
    await user.keyboard('{End}')
    expect(screen.getByRole('tab', { name: '模板' })).toHaveAttribute('aria-selected', 'true')
    await user.keyboard('{ArrowRight}')
    expect(screen.getByRole('tab', { name: '人群' })).toHaveFocus()
    expect(screen.getAllByRole('tab').filter((tab) => tab.tabIndex === 0)).toHaveLength(1)
  })
})
