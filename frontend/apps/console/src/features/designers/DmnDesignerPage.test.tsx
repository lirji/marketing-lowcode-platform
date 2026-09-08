import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { DmnDesignerPage } from './DmnDesignerPage'

describe('DmnDesignerPage', () => {
  it('starts from a blank table instead of the 家电会员优惠矩阵', () => {
    render(<DmnDesignerPage />)
    expect(screen.getByRole('heading', { name: 'DMN 草稿' })).toBeInTheDocument()
    expect(screen.queryByText('家电会员优惠矩阵')).not.toBeInTheDocument()
    expect(screen.queryByDisplayValue('满 500 减 80')).not.toBeInTheDocument()
    expect(screen.queryByDisplayValue('会员 92 折')).not.toBeInTheDocument()
    expect(screen.getByText('求值服务尚未接入')).toBeInTheDocument()
    expect(screen.getByText('尚未分析')).toBeInTheDocument()
  })

  it('does not invent overlap results for an empty table', async () => {
    const user = userEvent.setup()
    render(<DmnDesignerPage />)
    await user.click(screen.getByRole('button', { name: '分析表格' }))
    expect(screen.getByText('没有可分析的规则')).toBeInTheDocument()
    expect(screen.queryByText('发现 1 处重叠')).not.toBeInTheDocument()
    expect(screen.queryByText('无覆盖缺口')).not.toBeInTheDocument()
  })
})
