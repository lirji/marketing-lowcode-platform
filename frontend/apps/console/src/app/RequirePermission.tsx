import type { ReactNode } from 'react'
import { useAuth } from '../shared/auth/useAuth'
import { EmptyState } from '../components/ui'

export function RequirePermission({ anyOf, children }: { anyOf: string[]; children: ReactNode }) {
  const auth = useAuth()
  if (auth.hasAnyPermission(anyOf)) return children
  return (
    <div className="workspace">
      <EmptyState title="没有访问该模块的权限" detail={`需要以下权限之一：${anyOf.join('、')}。当前身份无法执行此操作。`} />
    </div>
  )
}
