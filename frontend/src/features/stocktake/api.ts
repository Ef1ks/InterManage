import { useMutation, useQuery } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type { Stocktake, StocktakeImport, StocktakeSummary } from '@/lib/types'

const path = (storeId: string) => `/stores/${storeId}/stocktakes`
const listKey = (storeId: string) => ['stores', storeId, 'stocktakes'] as const

export function useStocktakes(storeId: string) {
  return useQuery({
    queryKey: listKey(storeId),
    queryFn: () => api<StocktakeSummary[]>(path(storeId)),
  })
}

/** Inwentura po analizie: liczona przy każdym odczycie, bo zależy od sprzedaży i dostaw po inwenturze. */
export function useStocktake(storeId: string, id: string) {
  return useQuery({
    queryKey: [...listKey(storeId), id],
    queryFn: () => api<Stocktake>(`${path(storeId)}/${id}`),
    retry: false,
  })
}

/** Raport całego sklepu albo działu; inwenturę serwer rozpoznaje po numerze i dacie z nagłówka. */
export function useUploadStocktake(storeId: string) {
  return useMutation({
    mutationFn: (file: File) => {
      const formData = new FormData()
      formData.append('file', file)
      return api<StocktakeImport>(path(storeId), { method: 'POST', formData })
    },
  })
}

export function useDeleteStocktake(storeId: string) {
  return useMutation({
    mutationFn: (id: string) => api<void>(`${path(storeId)}/${id}`, { method: 'DELETE' }),
  })
}

export function useRestoreStocktake(storeId: string) {
  return useMutation({
    mutationFn: (id: string) => api<void>(`${path(storeId)}/${id}/restore`, { method: 'POST' }),
  })
}
