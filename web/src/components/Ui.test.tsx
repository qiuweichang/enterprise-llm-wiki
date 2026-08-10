import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Badge, Notice, timeAgo, timeUntil } from './Ui'

/** 通用 UI 组件的语义和时间格式测试。 */
describe('enterprise UI primitives', () => {
  /** 风险徽标应保留机器状态文本并生成语义类名。 */
  it('renders a risk badge', () => {
    render(<Badge value="HIGH" />)
    expect(screen.getByText('高风险')).toHaveClass('badge-high')
  })

  /** 提示条应展示内容和对应语义样式。 */
  it('renders a success notice', () => {
    render(<Notice kind="success">审核已发布</Notice>)
    expect(screen.getByText('审核已发布').closest('.notice')).toHaveClass('notice-success')
  })

  /** 最近时间应返回中文相对时间。 */
  it('formats recent timestamps', () => {
    expect(timeAgo(new Date(Date.now() - 120_000).toISOString())).toContain('分钟')
  })

  /** 后台下次运行时间应使用未来语义，避免误显示成“刚刚”。 */
  it('formats future scheduler timestamps', () => {
    expect(timeUntil(new Date(Date.now() + 120_000).toISOString())).toContain('分钟后')
  })
})
