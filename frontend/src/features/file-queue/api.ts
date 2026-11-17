import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type { FileQueueItem } from '@/lib/types'

export const fileQueueKey = (storeId: string) => ['stores', storeId, 'file-queue'] as const

/** Pliki sklepu czekające na zapis w folderach, w kolejności dodania. */
export function useFileQueue(storeId: string, enabled = true) {
  return useQuery({
    queryKey: fileQueueKey(storeId),
    queryFn: () => api<FileQueueItem[]>(`/stores/${storeId}/file-queue`),
    enabled,
  })
}

/** Liczba plików w kolejce (pasek pod nagłówkiem aplikacji), odświeżana co 5 minut. */
export function useFileQueueCount(storeId: string | undefined) {
  return useQuery({
    queryKey: [...fileQueueKey(storeId ?? ''), 'count'],
    queryFn: () => api<{ count: number }>(`/stores/${storeId}/file-queue/count`),
    enabled: !!storeId,
    select: (data) => data.count,
    refetchInterval: 5 * 60_000,
    staleTime: 60_000,
  })
}

export function useFileQueueRemoval(storeId: string) {
  const queryClient = useQueryClient()
  const refresh = () => queryClient.invalidateQueries({ queryKey: fileQueueKey(storeId) })
  const remove = useMutation({
    mutationFn: (id: string) => api(`/stores/${storeId}/file-queue/${id}`, { method: 'DELETE' }),
    onSuccess: refresh,
    meta: { invalidates: false },
  })
  const restore = useMutation({
    mutationFn: (id: string) => api(`/stores/${storeId}/file-queue/${id}/restore`, { method: 'POST' }),
    onSuccess: refresh,
    meta: { invalidates: false },
  })
  return { remove, restore }
}
