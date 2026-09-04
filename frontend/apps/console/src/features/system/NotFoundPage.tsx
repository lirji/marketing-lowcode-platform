import { Link } from 'react-router-dom'
import { EmptyState } from '../../components/ui'

export function NotFoundPage() {
  return <div className="workspace"><EmptyState title="页面没有找到" detail="这个入口可能已经迁移，返回总览继续操作。" action={<Link className="button primary" to="/">返回总览</Link>} /></div>
}
