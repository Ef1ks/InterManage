import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type { CollectorPromotion, CollectorPromotionDetails } from '@/lib/types'

import { collectorListsKey } from './lists-api'

/** Pod kluczem list z kolektorów: unieważnia je ta sama wiadomość na żywo `collector-lists`. */
const promotionsKey = (storeId: string) => [...collectorListsKey(storeId), 'promotions'] as const

const basePath = (storeId: string) => `/stores/${storeId}/collector-promotions`

/** Makro jeszcze się nie skończyło: czeka w kolejce, odlicza albo działa na komputerze sklepu. */
export const macroActive = (promotion: CollectorPromotion) =>
  promotion.macro !== null && ['QUEUED', 'OFFERED', 'RUNNING'].includes(promotion.macro.status)

export function useCollectorPromotions(storeId: string) {
  return useQuery({
    queryKey: [...promotionsKey(storeId), 'list'],
    queryFn: () => api<CollectorPromotion[]>(basePath(storeId)),
    // Stan makra zmienia się na komputerze sklepu; wiadomość na żywo przychodzi dopiero na końcu.
    refetchInterval: (query) => (query.state.data?.some(macroActive) ? 5_000 : 60_000),
  })
}

/** Promocje, których nikt nie wstawił ani nie ustawił makrem (licznik w menu). */
export function useWaitingCollectorPromotions(storeId: string, enabled: boolean) {
  return useQuery({
    queryKey: [...promotionsKey(storeId), 'count'],
    queryFn: () => api<{ waiting: number }>(`${basePath(storeId)}/count`),
    enabled,
    refetchInterval: 5 * 60_000,
  })
}

export function useCollectorPromotion(storeId: string, id: string | null) {
  return useQuery({
    queryKey: [...promotionsKey(storeId), 'details', id],
    queryFn: () => api<CollectorPromotionDetails>(`${basePath(storeId)}/${id}`),
    enabled: id !== null,
  })
}

function usePromotionMutation<T, R>(storeId: string, request: (value: T) => Promise<R>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: request,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: promotionsKey(storeId) }),
    meta: { invalidates: false },
  })
}

/** Ustawienie w Mercalys makrem na wolnym komputerze sklepu; po udanym cenówki idą do kolejki plików. */
export function useRunPromotionMacro(storeId: string) {
  return usePromotionMutation(storeId, (id: string) =>
    api<CollectorPromotion>(`${basePath(storeId)}/${id}/macro`, { method: 'POST', json: {} }),
  )
}

/** Promocje wstawione do Ustaw promocje (woła je strona Ustaw promocje po dopisaniu artykułów). */
export function markPromotionInserted(storeId: string, id: string) {
  return api<CollectorPromotion>(`${basePath(storeId)}/${id}/inserted`, { method: 'POST' })
}

export function useRemoveCollectorPromotion(storeId: string) {
  return usePromotionMutation(storeId, (id: string) =>
    api<CollectorPromotion[]>(`${basePath(storeId)}/${id}`, { method: 'DELETE' }),
  )
}

export function useRestoreCollectorPromotion(storeId: string) {
  return usePromotionMutation(storeId, (id: string) =>
    api<CollectorPromotion[]>(`${basePath(storeId)}/${id}/restore`, { method: 'POST' }),
  )
}
