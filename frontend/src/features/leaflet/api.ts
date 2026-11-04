import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type { Leaflet, LeafletMatch, LeafletSummary } from '@/lib/types'

const path = (storeId: string) => `/stores/${storeId}/leaflets`
const listKey = (storeId: string) => ['stores', storeId, 'leaflets'] as const

/** 30 ostatnich wgranych gazetek (bez zastąpionych), od najnowszego wgrania. */
export function useLeaflets(storeId: string) {
  return useQuery({
    queryKey: listKey(storeId),
    queryFn: () => api<LeafletSummary[]>(path(storeId)),
  })
}

/** Gazetka z pozycjami i artykułami z kartoteki (przypięte ręcznie, potem po EAN-ie z gazetki). */
export function useLeaflet(storeId: string, id: string | null) {
  return useQuery({
    queryKey: [...listKey(storeId), id],
    queryFn: () => api<Leaflet>(`${path(storeId)}/${id}`),
    enabled: id !== null,
    staleTime: 60_000,
    retry: false,
  })
}

/** Wgranie pliku gazetki; ta sama gazetka (rok i tydzień) wgrana ponownie zastępuje poprzednią. */
export function useUploadLeaflet(storeId: string) {
  return useMutation({
    mutationFn: (file: File) => {
      const formData = new FormData()
      formData.append('file', file)
      return api<Leaflet>(path(storeId), { method: 'POST', formData })
    },
  })
}

const eanKey = (ean: string) => ean.replace(/\D/g, '').replace(/^0+/, '')

/**
 * Przypięcie artykułu do EAN-u z gazetki, jego usunięcie (wraca przypięcie sprzed podmiany) i przywrócenie. Wynik
 * podmienia artykuł we wszystkich pozycjach otwartej gazetki z tym EAN-em, bez ponownego wczytywania gazetki.
 */
export function useLeafletLink(storeId: string, leafletId: string | null) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (
      action:
        | { type: 'link'; ean: string; itm8: string }
        | { type: 'unlink'; linkId: string }
        | { type: 'restore'; linkId: string },
    ) => {
      const base = `${path(storeId)}/links`
      if (action.type === 'link')
        return api<LeafletMatch>(base, { method: 'PUT', json: { ean: action.ean, itm8: action.itm8 } })
      if (action.type === 'unlink') return api<LeafletMatch>(`${base}/${action.linkId}`, { method: 'DELETE' })
      return api<LeafletMatch>(`${base}/${action.linkId}/restore`, { method: 'POST' })
    },
    onSuccess: (match) =>
      queryClient.setQueryData<Leaflet>([...listKey(storeId), leafletId], (leaflet) =>
        leaflet
          ? {
              ...leaflet,
              lines: leaflet.lines.map((line) =>
                eanKey(line.ean) === match.ean
                  ? { ...line, product: match.product, regularPrice: match.regularPrice, linkId: match.linkId }
                  : line,
              ),
            }
          : leaflet,
      ),
    meta: { invalidates: false },
  })
}
