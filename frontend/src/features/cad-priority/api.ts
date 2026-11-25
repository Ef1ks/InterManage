import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type { CadPriorityReport, CadPriorityRunResult } from '@/lib/types'

const path = (storeId: string) => `/stores/${storeId}/cadencier-priorities`
const key = (storeId: string) => ['stores', storeId, 'cadencier-priorities'] as const

export function useCadPriorities(storeId: string) {
  return useQuery({ queryKey: key(storeId), queryFn: () => api<CadPriorityReport>(path(storeId)) })
}

/** Poprawianie po aktualizacji danych na komputerze sklepu. */
export function useAutomaticCadPriorities(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (enabled: boolean) =>
      api<CadPriorityReport>(`${path(storeId)}/automatic`, { method: 'PUT', json: { enabled } }),
    onSuccess: (report) => queryClient.setQueryData(key(storeId), report),
    meta: { invalidates: false },
  })
}

/** Lista od nowa, np. po makrze na komputerze sklepu. */
export function useRefreshCadPriorities(storeId: string) {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: key(storeId) })
}

export type CadPriorityChoice = { itm8: string; topItm8: string }

/** Zlecenie zaznaczonych: makrem (treść wraca do schowka) albo jako ustawione ręcznie. */
export function useRunCadPriorities(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ macro, items }: { macro: boolean; items: CadPriorityChoice[] }) =>
      api<CadPriorityRunResult>(`${path(storeId)}/${macro ? 'macro' : 'runs'}`, { method: 'POST', json: { items } }),
    onSuccess: (result) => queryClient.setQueryData(key(storeId), result.report),
    meta: { invalidates: false },
  })
}

export function useUndoCadPriorities(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ ids, restore = false }: { ids: string[]; restore?: boolean }) =>
      api<CadPriorityReport>(`${path(storeId)}/runs/${restore ? 'restore' : 'undo'}`, {
        method: 'POST',
        json: { ids },
      }),
    onSuccess: (report) => queryClient.setQueryData(key(storeId), report),
    meta: { invalidates: false },
  })
}
