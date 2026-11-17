import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../auth/useAuth'
import type { AuthState } from '../auth/auth-context'
import { api } from './client'
import { RELEASE_PAGE_SIZES, type ReleaseStatus } from './schemas'

export function releaseScopeKey(auth: AuthState) {
  return JSON.stringify([auth.tenantId, auth.subject, [...auth.organizations].sort(), [...auth.shops].sort(), [...auth.permissions].sort()])
}

/** 发布列表按完整权限范围隔离，刷新丢弃旧游标；后页失败保留已读清单供重试。 */
export function useReleasePages(state?: ReleaseStatus, enabled = true) {
  const auth = useAuth(), client = useQueryClient()
  const canRead = auth.authenticated && auth.hasPermission('release:read')
  const queryKey = ['releases', 'pages', releaseScopeKey(auth), state ?? null] as const
  const query = useInfiniteQuery({
    queryKey, initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => api.releasePage({ state, limit: RELEASE_PAGE_SIZES.DEFAULT, cursor: pageParam }),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    enabled: enabled && canRead && !api.demoMode,
  })
  const items = enabled && canRead && !api.demoMode ? [...new Map((query.data?.pages.flatMap((page) => page.items) ?? []).map((item) => [item.manifest.manifestId, item])).values()] : []
  return { ...query, items, canRead, refresh: () => client.resetQueries({ queryKey, exact: true }) }
}
