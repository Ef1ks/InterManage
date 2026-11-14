import { useMutation, useQuery } from '@tanstack/react-query'

import { TOOL_ROUTES } from '@/features/tools/categories'
import { api } from '@/lib/api'
import type {
  CashClosing,
  CashClosingSummary,
  CashDocument,
  CashDocumentKind,
  CashReport,
  CashSettings,
} from '@/lib/types'

const base = TOOL_ROUTES.CASH_REPORT.path

export const cashPaths = {
  report: base,
  closings: `${base}/zakonczone`,
  closing: (id: string) => `${base}/zakonczone/${id}`,
  history: `${base}/dokumenty`,
}

export const closingPdfPath = (storeId: string, id: string) => `/stores/${storeId}/cash/closings/${id}/pdf`

const cashKey = (storeId: string) => ['stores', storeId, 'cash'] as const
export const cashPath = (storeId: string) => `/stores/${storeId}/cash`

export const documentPdfPath = (storeId: string, id: string) => `${cashPath(storeId)}/documents/${id}/pdf`
export const reportPdfPath = (storeId: string, from: string, to: string) =>
  `${cashPath(storeId)}/report/pdf?from=${from}&to=${to}`
export const stampUrl = (storeId: string, stampId: string) => `/api${cashPath(storeId)}/stamp/${stampId}`

export function useCashReport(storeId: string, from: string, to: string) {
  return useQuery({
    queryKey: [...cashKey(storeId), 'report', from, to],
    queryFn: () => api<CashReport>(`${cashPath(storeId)}/report?from=${from}&to=${to}`),
    enabled: from !== '' && to !== '' && from <= to,
  })
}

export function useCashHistory(storeId: string) {
  return useQuery({
    queryKey: [...cashKey(storeId), 'history'],
    queryFn: () => api<CashDocument[]>(`${cashPath(storeId)}/documents`),
  })
}

export function useCashSettings(storeId: string) {
  return useQuery({
    queryKey: [...cashKey(storeId), 'settings'],
    queryFn: () => api<CashSettings>(`${cashPath(storeId)}/settings`),
  })
}

/** Zakończone raporty dzienne (o 23:00), od najnowszego. */
export function useCashClosings(storeId: string) {
  return useQuery({
    queryKey: [...cashKey(storeId), 'closings'],
    queryFn: () => api<CashClosingSummary[]>(`${cashPath(storeId)}/closings`),
  })
}

export function useCashClosing(storeId: string, id: string | undefined) {
  return useQuery({
    queryKey: [...cashKey(storeId), 'closing', id],
    queryFn: () => api<CashClosing>(`${cashPath(storeId)}/closings/${id}`),
    enabled: id != null,
  })
}

export type CashDocumentForm = {
  kind: CashDocumentKind
  issueDate: string
  amount: number
  operation: string
  title: string | null
  counterparty: string | null
  counterpartyAddress: string | null
}

export function useCreateCashDocument(storeId: string) {
  return useMutation({
    mutationFn: (form: CashDocumentForm) =>
      api<CashDocument>(`${cashPath(storeId)}/documents`, { method: 'POST', json: form }),
  })
}

export function useCancelCashDocument(storeId: string) {
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) =>
      api<CashDocument>(`${cashPath(storeId)}/documents/${id}/cancel`, { method: 'POST', json: { reason } }),
  })
}

export function useRestoreCashDocument(storeId: string) {
  return useMutation({
    mutationFn: (id: string) => api<CashDocument>(`${cashPath(storeId)}/documents/${id}/restore`, { method: 'POST' }),
  })
}

export function useUploadStamp(storeId: string) {
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData()
      form.append('file', file, file.name)
      return api<CashSettings>(`${cashPath(storeId)}/stamp`, { method: 'PUT', formData: form })
    },
  })
}

export function useRemoveStamp(storeId: string) {
  return useMutation({
    mutationFn: () => api<CashSettings>(`${cashPath(storeId)}/stamp`, { method: 'DELETE' }),
  })
}

export function useRestoreStamp(storeId: string) {
  return useMutation({
    mutationFn: (stampId: string) =>
      api<CashSettings>(`${cashPath(storeId)}/stamp/${stampId}/restore`, { method: 'POST' }),
  })
}

export function useSetOpeningBalance(storeId: string) {
  return useMutation({
    mutationFn: (form: { date: string; amount: number }) =>
      api<CashSettings>(`${cashPath(storeId)}/opening-balance`, { method: 'PUT', json: form }),
  })
}
