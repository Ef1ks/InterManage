import { useInfiniteQuery, useQuery } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type { StoreEventFilters, StoreEventKind, StoreEventPage } from '@/lib/types'

export type StoreLogQuery = {
  kind: StoreEventKind | null
  module: string | null
  userId: string | null
  from: string | null
  to: string | null
  query: string
}

/** Dziennik sklepu, a z {@code storeId = null} dziennik firmy (tylko właściciel). */
function base(storeId: string | null) {
  return storeId ? `/stores/${storeId}/events` : '/settings/events'
}

export function useStoreLog(storeId: string | null, query: StoreLogQuery) {
  return useInfiniteQuery({
    queryKey: ['store-log', storeId ?? 'company', query],
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams()
      if (pageParam) {
        params.set('beforeAt', pageParam.at)
        params.set('beforeId', pageParam.id)
      }
      if (query.kind) params.set('kind', query.kind)
      if (query.module) params.set('module', query.module)
      if (query.userId) params.set('userId', query.userId)
      if (query.from) params.set('from', query.from)
      if (query.to) params.set('to', query.to)
      if (query.query.trim()) params.set('query', query.query.trim())
      const search = params.toString()
      return api<StoreEventPage>(`${base(storeId)}${search ? `?${search}` : ''}`)
    },
    initialPageParam: null as { at: string; id: string } | null,
    getNextPageParam: (last) => {
      const item = last.items.at(-1)
      return last.hasMore && item ? { at: item.occurredAt, id: item.id } : null
    },
    // Dziennik się dopisuje: pierwsza porcja odświeża się co minutę, gdy strona jest otwarta.
    refetchInterval: 60_000,
  })
}

export function useStoreLogFilters(storeId: string | null) {
  return useQuery({
    queryKey: ['store-log', storeId ?? 'company', 'filters'],
    queryFn: () => api<StoreEventFilters>(`${base(storeId)}/filters`),
    staleTime: 10 * 60_000,
  })
}
