import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type {
  BakeryArticle,
  BakeryBon,
  BakeryCheck,
  BakeryCheckSummary,
  BakeryComparison,
  BakeryInvoiceCandidate,
  BakeryScanReading,
  BakeryWz,
} from '@/lib/types'

const path = (storeId: string) => `/stores/${storeId}/bakery-checks`
const bakeryKey = (storeId: string) => ['stores', storeId, 'bakery-checks'] as const
const checkKey = (storeId: string, id: string) => [...bakeryKey(storeId), 'check', id] as const
const comparisonKey = (storeId: string, id: string) => [...checkKey(storeId, id), 'comparison'] as const

export const wzScanUrl = (storeId: string, checkId: string, wzId: string) =>
  `/api${path(storeId)}/${checkId}/wz/${wzId}/scan`

export function useBakeryBons(storeId: string, supplierId: string | null) {
  return useQuery({
    queryKey: [...bakeryKey(storeId), 'bons', supplierId],
    queryFn: () => api<BakeryBon[]>(`${path(storeId)}/deliveries?supplierId=${supplierId}`),
    enabled: supplierId !== null,
  })
}

export function useBakeryChecks(storeId: string, supplierId: string | null) {
  return useQuery({
    queryKey: [...bakeryKey(storeId), 'list', supplierId],
    queryFn: () => api<BakeryCheckSummary[]>(`${path(storeId)}?supplierId=${supplierId}`),
    enabled: supplierId !== null,
  })
}

export function useBakeryCheck(storeId: string, id: string) {
  return useQuery({
    queryKey: checkKey(storeId, id),
    queryFn: () => api<BakeryCheck>(`${path(storeId)}/${id}`),
  })
}

export function useBakeryComparison(storeId: string, id: string) {
  return useQuery({
    queryKey: comparisonKey(storeId, id),
    queryFn: () => api<BakeryComparison>(`${path(storeId)}/${id}/comparison`),
  })
}

export function useInvoiceCandidates(storeId: string, id: string, query: string, enabled: boolean) {
  return useQuery({
    queryKey: [...checkKey(storeId, id), 'invoice-candidates', query],
    queryFn: () =>
      api<BakeryInvoiceCandidate[]>(`${path(storeId)}/${id}/invoice-candidates?query=${encodeURIComponent(query)}`),
    enabled,
  })
}

export const fetchBakeryArticle = (storeId: string, itm8: string) =>
  api<BakeryArticle>(`${path(storeId)}/articles/${itm8}`)

export function useCreateBakeryCheck(storeId: string) {
  return useMutation({
    mutationFn: (payload: { supplierId: string; documentId: string | null }) =>
      api<BakeryCheck>(path(storeId), { method: 'POST', json: payload }),
  })
}

export function useDeleteBakeryCheck(storeId: string) {
  return useMutation({
    mutationFn: (id: string) => api<void>(`${path(storeId)}/${id}`, { method: 'DELETE' }),
  })
}

export function useRestoreBakeryCheck(storeId: string) {
  return useMutation({
    mutationFn: (id: string) => api<BakeryCheck>(`${path(storeId)}/${id}/restore`, { method: 'POST' }),
  })
}

export type CheckLink = { kind: 'documents' | 'invoices'; id: string }

export function useAttachToCheck(storeId: string, checkId: string) {
  return useMutation({
    mutationFn: ({ kind, id }: CheckLink) =>
      api<BakeryCheck>(`${path(storeId)}/${checkId}/${kind}`, { method: 'POST', json: { id } }),
  })
}

export function useDetachFromCheck(storeId: string, checkId: string) {
  return useMutation({
    mutationFn: ({ kind, id }: CheckLink) =>
      api<void>(`${path(storeId)}/${checkId}/${kind}/${id}`, { method: 'DELETE' }),
  })
}

export function useSyncWz(storeId: string, checkId: string) {
  return useMutation({
    mutationFn: () => api<BakeryCheck>(`${path(storeId)}/${checkId}/wz/sync`, { method: 'POST' }),
  })
}

export function useAddWz(storeId: string, checkId: string) {
  return useMutation({
    mutationFn: () => api<BakeryWz>(`${path(storeId)}/${checkId}/wz`, { method: 'POST' }),
  })
}

export type WzPayload = {
  number: string | null
  deliveryDate: string | null
  lines: { productId: string; quantity: number | null; scannedQuantity: number | null; manual: boolean }[]
}

/**
 * Zapis kafelka idzie sam, zaraz po wpisaniu, więc kolejne zapisy jednego WZ czekają na poprzedni (`scope`), a zamiast
 * unieważniać wszystko odświeża tylko podsumowanie. Ekran trzyma ilości u siebie, więc pisanie nie jest przerywane.
 */
export function useSaveWz(storeId: string, checkId: string, wzId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload: WzPayload) =>
      api<BakeryWz>(`${path(storeId)}/${checkId}/wz/${wzId}`, { method: 'PUT', json: payload }),
    scope: { id: `bakery-wz-${wzId}` },
    meta: { invalidates: false },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: comparisonKey(storeId, checkId) }),
  })
}

export function useDeleteWz(storeId: string, checkId: string) {
  return useMutation({
    mutationFn: (wzId: string) => api<void>(`${path(storeId)}/${checkId}/wz/${wzId}`, { method: 'DELETE' }),
  })
}

export function useRestoreWz(storeId: string, checkId: string) {
  return useMutation({
    mutationFn: (wzId: string) => api<BakeryWz>(`${path(storeId)}/${checkId}/wz/${wzId}/restore`, { method: 'POST' }),
  })
}

export function useAttachWzScan(storeId: string, checkId: string, wzId: string) {
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData()
      form.append('file', file, file.name)
      return api<BakeryWz>(`${path(storeId)}/${checkId}/wz/${wzId}/scan`, { method: 'PUT', formData: form })
    },
    meta: { invalidates: false },
  })
}

export function useDetachWzScan(storeId: string, checkId: string, wzId: string) {
  return useMutation({
    mutationFn: () => api<void>(`${path(storeId)}/${checkId}/wz/${wzId}/scan`, { method: 'DELETE' }),
    meta: { invalidates: false },
  })
}

export function useRestoreWzScan(storeId: string, checkId: string, wzId: string) {
  return useMutation({
    mutationFn: (scanId: string) =>
      api<BakeryWz>(`${path(storeId)}/${checkId}/wz/${wzId}/scan/restore`, { method: 'POST', json: { id: scanId } }),
    meta: { invalidates: false },
  })
}

export function useReadWzScan(storeId: string, checkId: string, wzId: string) {
  return useMutation({
    mutationFn: () => api<BakeryScanReading>(`${path(storeId)}/${checkId}/wz/${wzId}/scan/read`, { method: 'POST' }),
    meta: { invalidates: false },
  })
}
