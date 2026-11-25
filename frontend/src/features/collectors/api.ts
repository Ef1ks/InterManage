import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type { StoreCollector } from '@/lib/types'

const collectorsKey = (storeId: string) => ['stores', storeId, 'collectors'] as const

/** Kolektory sklepu; `waiting` (ktoś właśnie łączy kolektor) odświeża listę częściej. */
export function useCollectors(storeId: string, { enabled = true, waiting = false } = {}) {
  return useQuery({
    queryKey: collectorsKey(storeId),
    queryFn: () => api<StoreCollector[]>(`/stores/${storeId}/collectors`),
    enabled,
    refetchInterval: waiting ? 3_000 : 30_000,
  })
}

function useCollectorMutation<T>(storeId: string, request: (value: T) => Promise<StoreCollector[]>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: request,
    onSuccess: (data) => queryClient.setQueryData(collectorsKey(storeId), data),
    meta: { invalidates: false },
  })
}

export function useAddCollector(storeId: string) {
  return useCollectorMutation(storeId, (name: string) =>
    api<StoreCollector[]>(`/stores/${storeId}/collectors`, { method: 'POST', json: { name } }),
  )
}

export function useCollectorCode(storeId: string) {
  return useCollectorMutation(storeId, (id: string) =>
    api<StoreCollector[]>(`/stores/${storeId}/collectors/${id}/code`, { method: 'POST' }),
  )
}

export function useRemoveCollector(storeId: string) {
  return useCollectorMutation(storeId, (id: string) =>
    api<StoreCollector[]>(`/stores/${storeId}/collectors/${id}`, { method: 'DELETE' }),
  )
}

export function useRestoreCollector(storeId: string) {
  return useCollectorMutation(storeId, (id: string) =>
    api<StoreCollector[]>(`/stores/${storeId}/collectors/${id}/restore`, { method: 'POST' }),
  )
}
