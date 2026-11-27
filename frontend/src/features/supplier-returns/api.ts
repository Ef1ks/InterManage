import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type {
  SupplierReturnCandidates,
  SupplierReturnDetails,
  SupplierReturnStatus,
  SupplierReturnSummary,
} from '@/lib/types'

export const supplierReturnsKey = (storeId: string) => ['stores', storeId, 'supplier-returns'] as const
const basePath = (storeId: string) => `/stores/${storeId}/supplier-returns`

export const STATUS_LABELS: Record<SupplierReturnStatus, string> = {
  NEW: 'Do zgłoszenia',
  REPORTED: 'Zgłoszony',
  IN_PROGRESS: 'W trakcie',
  ACCEPTED: 'Uznany',
  CORRECTED: 'Rozliczony korektą',
  REJECTED: 'Odrzucony',
}

export const STATUS_ORDER: SupplierReturnStatus[] = [
  'NEW',
  'REPORTED',
  'IN_PROGRESS',
  'ACCEPTED',
  'CORRECTED',
  'REJECTED',
]

/** Mocne kolory stanu (bursztyn czeka na nas, niebieski u dostawcy, zielony rozliczony, czerwony odrzucony). */
export const STATUS_STYLES: Record<SupplierReturnStatus, string> = {
  NEW: 'bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100',
  REPORTED: 'bg-sky-200 text-sky-950 dark:bg-sky-900/70 dark:text-sky-100',
  IN_PROGRESS: 'bg-indigo-200 text-indigo-950 dark:bg-indigo-900/70 dark:text-indigo-100',
  ACCEPTED: 'bg-teal-200 text-teal-950 dark:bg-teal-900/70 dark:text-teal-100',
  CORRECTED: 'bg-emerald-200 text-emerald-950 dark:bg-emerald-900/70 dark:text-emerald-100',
  REJECTED: 'bg-rose-200 text-rose-950 dark:bg-rose-900/70 dark:text-rose-100',
}

export const isOpen = (status: SupplierReturnStatus) => status !== 'CORRECTED' && status !== 'REJECTED'

export function useSupplierReturns(storeId: string) {
  return useQuery({
    queryKey: [...supplierReturnsKey(storeId), 'list'],
    queryFn: () => api<SupplierReturnSummary[]>(basePath(storeId)),
  })
}

export function useOpenSupplierReturns(storeId: string, enabled: boolean) {
  return useQuery({
    queryKey: [...supplierReturnsKey(storeId), 'count'],
    queryFn: () => api<{ open: number }>(`${basePath(storeId)}/count`),
    enabled,
    refetchInterval: 5 * 60_000,
  })
}

export function useSupplierReturn(storeId: string, id: string | null) {
  return useQuery({
    queryKey: [...supplierReturnsKey(storeId), 'details', id],
    queryFn: () => api<SupplierReturnDetails>(`${basePath(storeId)}/${id}`),
    enabled: id !== null,
  })
}

export function useSupplierReturnCandidates(storeId: string, id: string, enabled: boolean) {
  return useQuery({
    queryKey: [...supplierReturnsKey(storeId), 'candidates', id],
    queryFn: () => api<SupplierReturnCandidates>(`${basePath(storeId)}/${id}/candidates`),
    enabled,
  })
}

export type ReturnLine = {
  itm8: string | null
  ean: string | null
  name: string
  brand: string | null
  quantity: number
}

/** Zmiany zwrotu odpowiadają całym zwrotem; odświeżają listę i kandydatów (nie całą aplikację). */
export function useSupplierReturnActions(storeId: string) {
  const queryClient = useQueryClient()
  const options = {
    meta: { invalidates: false },
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: supplierReturnsKey(storeId) }),
  }
  const put = (id: string, part: string, json: unknown) =>
    api<SupplierReturnDetails>(`${basePath(storeId)}/${id}/${part}`, { method: 'PUT', json })

  return {
    fromCollector: useMutation({
      ...options,
      mutationFn: (input: { listIds: string[]; note?: string }) =>
        api<SupplierReturnDetails>(`${basePath(storeId)}/from-collector`, { method: 'POST', json: input }),
    }),
    fromProtocols: useMutation({
      ...options,
      mutationFn: (input: { lines: ReturnLine[]; supplierId?: string | null; note?: string }) =>
        api<SupplierReturnDetails>(`${basePath(storeId)}/from-protocols`, { method: 'POST', json: input }),
    }),
    status: useMutation({
      ...options,
      mutationFn: ({ id, status, comment }: { id: string; status: SupplierReturnStatus; comment?: string }) =>
        put(id, 'status', { status, comment }),
    }),
    supplier: useMutation({
      ...options,
      mutationFn: ({ id, supplierId }: { id: string; supplierId: string | null }) =>
        put(id, 'supplier', { supplierId }),
    }),
    note: useMutation({
      ...options,
      mutationFn: ({ id, note }: { id: string; note: string }) => put(id, 'note', { note }),
    }),
    attach: useMutation({
      ...options,
      mutationFn: ({ id, part, documentId }: { id: string; part: AttachPart; documentId: string | null }) =>
        put(id, part, { id: documentId }),
    }),
    remove: useMutation({
      ...options,
      mutationFn: (id: string) => api(`${basePath(storeId)}/${id}`, { method: 'DELETE' }),
    }),
    restore: useMutation({
      ...options,
      mutationFn: (id: string) => api(`${basePath(storeId)}/${id}/restore`, { method: 'POST' }),
    }),
  }
}

export type AttachPart = 'delivery' | 'correction' | 'invoice'

export const supplierReturnFile = (storeId: string, id: string, kind: 'xlsx' | 'pdf') =>
  `${basePath(storeId)}/${id}/${kind}`
