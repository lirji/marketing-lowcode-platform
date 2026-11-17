import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../auth/useAuth'
import type { AuthState } from '../auth/auth-context'
import { api } from './client'
import { APPROVAL_PAGE_SIZES, type ApprovalStatus } from './schemas'

export const APPROVAL_STATUS = { OPEN: 'OPEN', APPROVED: 'APPROVED', REJECTED: 'REJECTED' } as const
export const APPROVAL_READ_PERMISSIONS = ['definition:read', 'approval:business', 'approval:finance', 'approval:compliance', 'approval:merchant']
export function approvalScopeKey(auth: AuthState) {
  return JSON.stringify([auth.tenantId, auth.subject, [...auth.organizations].sort(), [...auth.shops].sort(), [...auth.permissions].sort()])
}

/** 页游标和结果绑定完整权限范围；状态切换使用独立缓存，刷新丢弃旧游标重新读取。 */
export function useApprovalPages(status?: ApprovalStatus, enabled = true, limit: number = APPROVAL_PAGE_SIZES.DEFAULT) {
  const auth = useAuth(), client = useQueryClient()
  const canRead = auth.authenticated && auth.hasAnyPermission(APPROVAL_READ_PERMISSIONS)
  const queryKey = ['approvals', 'pages', approvalScopeKey(auth), status ?? null, limit] as const
  const query = useInfiniteQuery({
    queryKey, initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => api.approvalPage({ status, limit, cursor: pageParam }),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    enabled: enabled && canRead && !api.demoMode,
  })
  const items = canRead && !api.demoMode ? [...new Map((query.data?.pages.flatMap((page) => page.items) ?? []).map((item) => [item.caseId, item])).values()] : []
  return { ...query, items, canRead, refresh: () => client.resetQueries({ queryKey, exact: true }) }
}
