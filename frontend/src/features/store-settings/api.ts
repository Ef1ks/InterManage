import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type { StorePartners } from '@/lib/types'

/** Sklepy zaprzyjaźnione i zaproszenia wybranego sklepu (z obu stron) oraz sklepy, które można zaprosić. */
export const partnersKey = (storeId: string) => ['stores', storeId, 'partners'] as const
const partnersPath = (storeId: string) => `/stores/${storeId}/partners`

export function useStorePartners(storeId: string, enabled = true) {
  return useQuery({
    queryKey: partnersKey(storeId),
    queryFn: () => api<StorePartners>(partnersPath(storeId)),
    enabled,
  })
}

/** Każda zmiana zwraca całą listę, którą od razu podmieniamy. */
export function useStorePartnerActions(storeId: string) {
  const queryClient = useQueryClient()
  const onSuccess = (partners: StorePartners) => queryClient.setQueryData(partnersKey(storeId), partners)
  return {
    invite: useMutation({
      mutationFn: (partnerStoreId: string) =>
        api<StorePartners>(partnersPath(storeId), { method: 'POST', json: { storeId: partnerStoreId } }),
      onSuccess,
    }),
    accept: useMutation({
      mutationFn: (id: string) => api<StorePartners>(`${partnersPath(storeId)}/${id}/accept`, { method: 'POST' }),
      onSuccess,
    }),
    remove: useMutation({
      mutationFn: (id: string) => api<StorePartners>(`${partnersPath(storeId)}/${id}`, { method: 'DELETE' }),
      onSuccess,
    }),
    restore: useMutation({
      mutationFn: (id: string) => api<StorePartners>(`${partnersPath(storeId)}/${id}/restore`, { method: 'POST' }),
      onSuccess,
    }),
  }
}
