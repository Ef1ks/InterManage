import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type { CollectorList, CollectorListDetails, ProtocolKind } from '@/lib/types'

/** Unieważnia ją wiadomość na żywo `collector-lists` (kolektor wysłał listę). */
export const collectorListsKey = (storeId: string) => ['stores', storeId, 'collector-lists'] as const

const basePath = (storeId: string) => `/stores/${storeId}/collector-lists`

export const listQuery = (ids: string[]) => ids.map((id) => `ids=${id}`).join('&')

export function useCollectorLists(storeId: string) {
  return useQuery({
    queryKey: [...collectorListsKey(storeId), 'list'],
    queryFn: () => api<CollectorList[]>(basePath(storeId)),
    refetchInterval: 60_000,
  })
}

/** Listy czekające na wybór protokołu (licznik w menu). */
export function useWaitingCollectorLists(storeId: string, enabled: boolean) {
  return useQuery({
    queryKey: [...collectorListsKey(storeId), 'count'],
    queryFn: () => api<{ waiting: number }>(`${basePath(storeId)}/count`),
    enabled,
    refetchInterval: 5 * 60_000,
  })
}

export function useCollectorListDetails(storeId: string, ids: string[]) {
  return useQuery({
    queryKey: [...collectorListsKey(storeId), 'details', ...ids],
    queryFn: () => api<CollectorListDetails>(`${basePath(storeId)}/details?${listQuery(ids)}`),
    enabled: ids.length > 0,
  })
}

function useListMutation<T>(storeId: string, request: (value: T) => Promise<CollectorList[]>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: request,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: collectorListsKey(storeId) }),
    meta: { invalidates: false },
  })
}

/** Plik protokołu leży już w folderze: listy przestają czekać. */
export function useMarkProtocolSaved(storeId: string) {
  return useListMutation(storeId, ({ kind, ids, fileName }: { kind: ProtocolKind; ids: string[]; fileName: string }) =>
    api<CollectorList[]>(`${basePath(storeId)}/protocols/${kind}/saved`, { method: 'POST', json: { ids, fileName } }),
  )
}

export function useUndoProtocol(storeId: string) {
  return useListMutation(storeId, ({ listId, protocolId }: { listId: string; protocolId: string }) =>
    api<CollectorList[]>(`${basePath(storeId)}/${listId}/protocols/${protocolId}`, { method: 'DELETE' }),
  )
}

export function useRemoveCollectorList(storeId: string) {
  return useListMutation(storeId, (id: string) =>
    api<CollectorList[]>(`${basePath(storeId)}/${id}`, { method: 'DELETE' }),
  )
}

export function useRestoreCollectorList(storeId: string) {
  return useListMutation(storeId, (id: string) =>
    api<CollectorList[]>(`${basePath(storeId)}/${id}/restore`, { method: 'POST' }),
  )
}
