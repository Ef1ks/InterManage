import { useMutation, useQuery } from '@tanstack/react-query'

import { TOOL_ROUTES } from '@/features/tools/categories'
import { api } from '@/lib/api'
import type {
  MeatArticle,
  MeatDelivery,
  MeatDeliverySummary,
  MeatReport,
  MeatReportMode,
  MeatScanResult,
} from '@/lib/types'

const base = TOOL_ROUTES.MEAT_PURCHASES.path

export const meatPaths = {
  deliveries: base,
  report: `${base}/porownanie`,
  create: `${base}/wz/nowy`,
  fromScan: (scanId: string) => `${base}/wz/nowy?skan=${scanId}`,
  delivery: (id: string) => `${base}/wz/${id}`,
}

const meatKey = (storeId: string) => ['stores', storeId, 'meat'] as const
const path = (storeId: string) => `/stores/${storeId}/meat-deliveries`

export const scanFileUrl = (storeId: string, scanId: string) => `/api${path(storeId)}/scans/${scanId}`

/** Odczyt skanu z ostatniego wgrania; trzymany w pamięci zapytań, żeby edytor dostał go po przejściu na podstronę. */
export const scanKey = (storeId: string, scanId: string) => [...meatKey(storeId), 'scan', scanId] as const

export function useMeatArticles(storeId: string) {
  return useQuery({
    queryKey: [...meatKey(storeId), 'articles'],
    queryFn: () => api<MeatArticle[]>(`${path(storeId)}/articles`),
    staleTime: 5 * 60_000,
  })
}

export function useMeatDeliveries(storeId: string, from: string, to: string) {
  return useQuery({
    queryKey: [...meatKey(storeId), 'deliveries', from, to],
    queryFn: () => api<MeatDeliverySummary[]>(`${path(storeId)}?from=${from}&to=${to}`),
  })
}

export function useMeatDelivery(storeId: string, id: string | undefined) {
  return useQuery({
    queryKey: [...meatKey(storeId), 'delivery', id],
    queryFn: () => api<MeatDelivery>(`${path(storeId)}/${id}`),
    enabled: id != null,
  })
}

export type MeatReportParams = { from: string; to: string; mode: MeatReportMode; stores: string[] }

export function useMeatReport(storeId: string, { from, to, mode, stores }: MeatReportParams) {
  const query = new URLSearchParams({ from, to, mode })
  stores.forEach((store) => query.append('stores', store))
  return useQuery({
    queryKey: [...meatKey(storeId), 'report', from, to, mode, stores],
    queryFn: () => api<MeatReport>(`${path(storeId)}/report?${query}`),
    enabled: stores.length > 0,
  })
}

/** Odczyt skanu przez AI: zapisuje sam skan, więc nie unieważnia danych na ekranie. */
export function useScanMeatDocument(storeId: string) {
  return useMutation({
    mutationFn: (file: Blob & { name?: string }) => {
      const form = new FormData()
      form.append('file', file, file.name ?? 'wz.jpg')
      return api<MeatScanResult>(`${path(storeId)}/scan`, { method: 'POST', formData: form })
    },
    meta: { invalidates: false },
  })
}

export type MeatDeliveryForm = {
  scanId: string | null
  supplierId: string
  documentNumber: string
  deliveryDate: string
  vetNumber: string | null
  bdo: string | null
  nip: string | null
  lines: {
    documentName: string
    documentCode: string | null
    unitPrice: number
    productId: string | null
    handwrittenCorrection: boolean
    note: string | null
  }[]
}

export function useSaveMeatDelivery(storeId: string, id: string | undefined) {
  return useMutation({
    mutationFn: (form: MeatDeliveryForm) =>
      api<MeatDelivery>(id ? `${path(storeId)}/${id}` : path(storeId), {
        method: id ? 'PUT' : 'POST',
        json: form,
      }),
  })
}

export function useDeleteMeatDelivery(storeId: string) {
  return useMutation({
    mutationFn: (id: string) => api(`${path(storeId)}/${id}`, { method: 'DELETE' }),
  })
}

export function useRestoreMeatDelivery(storeId: string) {
  return useMutation({
    mutationFn: (id: string) => api<MeatDelivery>(`${path(storeId)}/${id}/restore`, { method: 'POST' }),
  })
}
