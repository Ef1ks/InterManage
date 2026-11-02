import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type { BaseDelivery, BaseDeliverySummary, MeaOverview } from '@/lib/types'

const path = (storeId: string) => `/stores/${storeId}/base-deliveries`
const listKey = (storeId: string) => ['stores', storeId, 'base-deliveries'] as const
const meaKey = (storeId: string) => ['stores', storeId, 'mea-codes'] as const

/** 30 ostatnich wgranych dokumentów dostaw z bazy (bez zastąpionych), od najnowszej dostawy. */
export function useBaseDeliveries(storeId: string, enabled = true) {
  return useQuery({
    queryKey: listKey(storeId),
    queryFn: () => api<BaseDeliverySummary[]>(path(storeId)),
    enabled,
  })
}

/** Bon z liniami i artykułami z kartoteki (szukanymi po ITM8, potem po EAN-ie). */
export function useBaseDelivery(storeId: string, id: string | null) {
  return useQuery({
    queryKey: [...listKey(storeId), id],
    queryFn: () => api<BaseDelivery>(`${path(storeId)}/${id}`),
    enabled: id !== null,
    staleTime: 60_000,
    retry: false,
  })
}

/** Wgranie pliku .xls z bazy; ten sam bon wgrany ponownie zastępuje poprzedni. */
export function useUploadBaseDelivery(storeId: string) {
  return useMutation({
    mutationFn: (file: File) => {
      const formData = new FormData()
      formData.append('file', file)
      return api<BaseDelivery>(path(storeId), { method: 'POST', formData })
    },
  })
}

export const shortDatesPdfPath = (storeId: string, id: string) => `${path(storeId)}/${id}/short-dates/pdf`

export function useMeaOverview(storeId: string) {
  return useQuery({
    queryKey: meaKey(storeId),
    queryFn: () => api<MeaOverview>(`/stores/${storeId}/mea-codes`),
  })
}

/** Potwierdzenie wpisania kodów, jego cofnięcie (znacznik) i przywrócenie; każde zwraca cały przegląd. */
export function useMeaChange(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (
      action: { type: 'confirm'; codes: string[] } | { type: 'delete'; id: string } | { type: 'restore'; id: string },
    ) => {
      const base = `/stores/${storeId}/mea-codes/confirmations`
      if (action.type === 'confirm') return api<MeaOverview>(base, { method: 'POST', json: { codes: action.codes } })
      if (action.type === 'delete') return api<MeaOverview>(`${base}/${action.id}`, { method: 'DELETE' })
      return api<MeaOverview>(`${base}/${action.id}/restore`, { method: 'POST' })
    },
    onSuccess: (overview) => queryClient.setQueryData(meaKey(storeId), overview),
    meta: { invalidates: false },
  })
}
