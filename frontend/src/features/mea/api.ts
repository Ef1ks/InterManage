import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type { MeaDocument, MeaLeafletSummary, MeaList, MeaQueueItem, MeaSettings } from '@/lib/types'

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

/** Kolejka plików do ATHOS sklepu (pliki zrobione u siebie, czekające na „Wyślij wszystkie”). */
export function useAthosQueue(storeId: string) {
  return useQuery({
    queryKey: [...listKey(storeId), 'athos-queue'],
    queryFn: () => api<MeaQueueItem[]>(`/stores/${storeId}/mea/athos-queue`),
    refetchInterval: 60 * 1000,
  })
}

/**
 * Po zmianie kolejki odświeżamy tylko ją i dokumenty MEA, bez listy z portalu (to kilka sekund czekania, a kolejka jej
 * nie zmienia).
 */
function useRefreshQueue(storeId: string) {
  const queryClient = useQueryClient()
  return () => {
    void queryClient.invalidateQueries({ queryKey: [...listKey(storeId), 'athos-queue'] })
    void queryClient.invalidateQueries({ queryKey: [...listKey(storeId), 'documents'] })
  }
}

/** Zamówienie do kolejki sklepu; plik tego MEA, który jeszcze czekał, zastępuje nowy. */
export function useQueueAthosOrder(storeId: string, number: string) {
  const refresh = useRefreshQueue(storeId)
  return useMutation({
    mutationFn: (lines: { itm8: string; colis: number[] }[]) =>
      api<MeaQueueItem>(`/stores/${storeId}/mea/documents/${number}/athos-queue`, {
        method: 'POST',
        json: { lines },
      }),
    onSuccess: refresh,
    meta: { invalidates: false },
  })
}

/** Zdejmuje plik z kolejki (znacznik) albo przywraca go. */
export function useAthosQueueRemoval(storeId: string) {
  const refresh = useRefreshQueue(storeId)
  const remove = useMutation({
    mutationFn: (id: string) => api(`/stores/${storeId}/mea/athos-queue/${id}`, { method: 'DELETE' }),
    onSuccess: refresh,
    meta: { invalidates: false },
  })
  const restore = useMutation({
    mutationFn: (id: string) => api(`/stores/${storeId}/mea/athos-queue/${id}/restore`, { method: 'POST' }),
    onSuccess: refresh,
    meta: { invalidates: false },
  })
  return { remove, restore }
}

/** Wgrane opisy gazetek z centrali (wspólne dla sklepów). */
export function useMeaLeaflets(storeId: string, enabled = true) {
  return useQuery({
    queryKey: [...listKey(storeId), 'leaflets'],
    queryFn: () => api<MeaLeafletSummary[]>(`/stores/${storeId}/mea/leaflets`),
    enabled,
  })
}

/** Po nowym opisie odświeżamy opisy i dokumenty MEA, bez listy z portalu (to kilka sekund czekania). */
function useRefreshLeaflets(storeId: string) {
  const queryClient = useQueryClient()
  return () => {
    void queryClient.invalidateQueries({ queryKey: [...listKey(storeId), 'leaflets'] })
    void queryClient.invalidateQueries({ queryKey: [...listKey(storeId), 'documents'] })
  }
}

/**
 * Sprawdzenie portalu od razu (w tle robi to zadanie MEA co kwadrans): pobiera opisy gazetek, których jeszcze nie ma.
 */
export function useSyncMeaLeaflets(storeId: string) {
  const refresh = useRefreshLeaflets(storeId)
  return useMutation({
    mutationFn: () =>
      api<{ downloaded: number; leaflets: MeaLeafletSummary[] }>(`/stores/${storeId}/mea/leaflets/sync`, {
        method: 'POST',
      }),
    onSuccess: refresh,
    meta: { invalidates: false },
  })
}

/** Ręczne wgranie opisu (gdy portal nie działa); ta sama gazetka wgrana ponownie zastępuje poprzednią. */
export function useUploadMeaLeaflet(storeId: string) {
  const refresh = useRefreshLeaflets(storeId)
  return useMutation({
    mutationFn: (file: File) => {
      const formData = new FormData()
      formData.append('file', file)
      return api<MeaLeafletSummary>(`/stores/${storeId}/mea/leaflets`, { method: 'POST', formData })
    },
    onSuccess: refresh,
    meta: { invalidates: false },
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
