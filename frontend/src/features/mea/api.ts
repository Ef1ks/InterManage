import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type { MeaDocument, MeaList, MeaSettings } from '@/lib/types'

const listKey = (storeId: string) => ['stores', storeId, 'mea'] as const
const settingsKey = (storeId: string) => ['stores', storeId, 'mea', 'settings'] as const

/** Lista MEA sklepu z portalu (loguje się kluczem API sklepu). Nie ponawiamy: błąd portalu pokazujemy od razu. */
export function useMeaList(storeId: string, enabled = true) {
  return useQuery({
    queryKey: listKey(storeId),
    queryFn: () => api<MeaList>(`/stores/${storeId}/mea`),
    enabled,
    // Lista z portalu to ponad tysiąc MEA i kilka sekund czekania, więc powrót z dokumentu jej nie pobiera od nowa
    // (świeżą daje „Odśwież”).
    staleTime: 5 * 60 * 1000,
    retry: false,
  })
}

/**
 * Dokument MEA (formularz z portalu z artykułami z kartoteki). Formularz się nie zmienia, więc trzymamy go długo;
 * stan i rotację odświeża ponowne wejście po 10 min.
 */
export function useMeaDocument(storeId: string, number: string) {
  return useQuery({
    queryKey: [...listKey(storeId), 'documents', number],
    queryFn: () => api<MeaDocument>(`/stores/${storeId}/mea/documents/${number}`),
    staleTime: 10 * 60 * 1000,
    retry: false,
  })
}

/** Stan kluczy API sklepu (czy zapisane), bez samych kluczy. */
export function useMeaSettings(storeId: string) {
  return useQuery({
    queryKey: settingsKey(storeId),
    queryFn: () => api<MeaSettings>(`/stores/${storeId}/mea/settings`),
  })
}

/** Zapis kluczy API sklepu; pusty sekret zostawia dotychczasowy. Odświeża listę. */
export function useSaveMeaSettings(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: { apiKey: string; apiSecret: string }) =>
      api<MeaSettings>(`/stores/${storeId}/mea/settings`, { method: 'PUT', json: body }),
    onSuccess: (settings) => {
      queryClient.setQueryData(settingsKey(storeId), settings)
      void queryClient.invalidateQueries({ queryKey: listKey(storeId) })
    },
    meta: { invalidates: false },
  })
}
