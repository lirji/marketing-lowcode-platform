import { useEffect, useState } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { api } from '../../shared/api/client'
import { BENEFIT_PAGE_SIZES } from '../../shared/api/schemas'
import { problemDetail } from '../../shared/api/problem'
import { useAuth } from '../../shared/auth/useAuth'
import { Button, EmptyState, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { AssetTable } from './AssetTable'

// 数据库包含搜索可能扫描当前租户，停止连续输入后再查询，避免逐键触发请求。
const SEARCH_DEBOUNCE_MS = 300

/** 搜索覆盖当前租户的全部最新定义；界面只报告实际加载量，更多失败不丢掉前页。 */
export function BenefitAssetList() {
  const auth = useAuth()
  const [query, setQuery] = useState('')
  const normalizedQuery = query.trim()
  const [search, setSearch] = useState('')
  useEffect(() => {
    const timer = window.setTimeout(() => setSearch(normalizedQuery), SEARCH_DEBOUNCE_MS)
    return () => window.clearTimeout(timer)
  }, [normalizedQuery])
  const searchPending = normalizedQuery !== search
  const live = !api.demoMode && auth.hasPermission('benefit:read')
  const page = useInfiniteQuery({
    queryKey: ['benefit-assets', auth.tenantId, auth.subject, auth.organizations, auth.shops, auth.permissions, search],
    queryFn: ({ pageParam }) => api.benefitPage({ q: search, limit: BENEFIT_PAGE_SIZES.DEFAULT, cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: last => last.hasMore ? last.nextCursor ?? undefined : undefined,
    enabled: live,
  })
  if (!live) return <Panel><EmptyState title={api.demoMode ? '权益列表需要连接服务' : '没有查看权益的权限'} detail={api.demoMode ? '静态预览不查询、刷新或展示业务记录；连接营销服务后读取已保存的版本。' : '请使用具备相应读取权限的身份。'} /></Panel>
  const items = searchPending ? [] : page.data?.pages.flatMap(item => item.items) ?? []
  const rows = items.map(item => [item.name, item.benefitId, String(item.version), item.benefitSkuId ?? '未绑定 SKU', item.createdAt ?? '', item.status])
  const initialError = !searchPending && page.isError && items.length === 0
  const bodyState = (page.isPending || searchPending) && live ? <div className="skeleton-list" aria-label="正在加载权益"><i /><i /></div>
    : initialError ? <><StateBanner tone="error" title="权益加载失败" detail={problemDetail(page.error)} /><Button onClick={() => void page.refetch()}>重试权益查询</Button></>
      : !items.length && !search ? <EmptyState title="没有权益定义" detail="当前租户尚无可见权益。请到权益设计器保存后再刷新。" /> : undefined
  return <Panel><PanelHeader eyebrow="BENEFIT CATALOG" title="权益最新版本" />
    <AssetTable label="权益" columns={['权益名称', '权益 ID', '版本', '绑定 SKU', '创建时间', '状态']} rows={rows}
      search={{ value: query, onChange: setQuery, onRefresh: () => { void page.refetch() }, busy: page.isFetching || searchPending, countKnown: !searchPending && Boolean(page.data) }} bodyState={bodyState}
      editHref={auth.hasPermission('benefit:write') ? row => `/designers/benefit?benefitId=${encodeURIComponent(row[1])}` : undefined} />
    {!searchPending && page.isFetchNextPageError && <StateBanner tone="error" title="下一页权益加载失败" detail={problemDetail(page.error)} />}
    {!searchPending && page.isRefetchError && items.length > 0 && <StateBanner tone="error" title="权益刷新失败" detail="当前保留上次结果，请重试刷新。" />}
    {live && !searchPending && page.hasNextPage && <Button onClick={() => void page.fetchNextPage()} disabled={page.isFetching}>{page.isFetchingNextPage ? '正在加载更多权益…' : page.isFetchNextPageError ? '重试下一页权益' : '加载更多权益'}</Button>}
    {live && !searchPending && !page.isPending && !initialError && <p className="field-hint">已加载 {items.length} 项；按权益 ID 升序，仅展示每个权益的最新版本。{page.hasNextPage ? '还有更多结果。' : '当前搜索已全部加载。'} 新增或更新后可刷新列表。</p>}
  </Panel>
}
