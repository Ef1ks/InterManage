import { useMutation, useQuery } from '@tanstack/react-query'

import { api, downloadPostedFile } from '@/lib/api'
import type {
  DataEntryCatalogProduct,
  DataEntryInvoice,
  NomenclatureItem,
  SuggestionResponse,
  SuggestionStatus,
} from '@/lib/types'

const path = (storeId: string) => `/stores/${storeId}/data-entry`

/** Cała nomenklatura sklepu: nazwy rejonów, rodzin i podrodzin w tabeli i wyszukiwarka podrodzin. */
export function useDataEntryNomenclature(storeId: string) {
  return useQuery({
    queryKey: ['stores', storeId, 'data-entry', 'nomenclatures'],
    queryFn: () => api<NomenclatureItem[]>(`${path(storeId)}/nomenclatures`),
    staleTime: 10 * 60_000,
  })
}

/** Pozycje faktury z KSeF z artykułami z kartoteki (za pierwszym razem serwer pobiera XML z KSeF). */
export function useLoadInvoice(storeId: string) {
  return useMutation({
    mutationFn: (invoiceId: string) => api<DataEntryInvoice>(`${path(storeId)}/invoices/${invoiceId}`),
    meta: { invalidates: false },
  })
}

/** Artykuły z kartoteki dla wpisanych albo wklejonych EAN-ów. */
export function lookupProducts(storeId: string, eans: string[]) {
  const params = new URLSearchParams()
  eans.forEach((ean) => params.append('ean', ean))
  return api<DataEntryCatalogProduct[]>(`${path(storeId)}/products?${params}`)
}

export type SuggestionPayload = {
  invoiceId: string | null
  sellerNip: string | null
  sellerName: string | null
  examples: { invoiceName: string; catalogName: string; brand: string | null }[]
  items: { key: string; ean: string | null; invoiceName: string; invoiceUnit: string | null }[]
}

/** "Uzupełnij z AI": nazwa, marka, nomenklatura, rodzaj sprzedaży i waga nowych artykułów. */
export function useSuggest(storeId: string) {
  return useMutation({
    mutationFn: (payload: SuggestionPayload) =>
      api<SuggestionResponse>(`${path(storeId)}/suggestions`, { method: 'POST', json: payload }),
    meta: { invalidates: false },
  })
}

export type ExportPayload = {
  invoiceNumber: string | null
  sellerName: string | null
  rows: {
    suggestionId: string | null
    ean: string | null
    quantity: number | null
    unitNet: number | null
    invoiceUnit: string | null
    name: string
    brand: string | null
    vatPercent: number | null
    salePrice: number | null
    subfamilyCode: string | null
    unit: string
    netContent: number
    status: SuggestionStatus | null
    known: boolean
  }[]
}

export function useExport(storeId: string) {
  return useMutation({
    mutationFn: (payload: ExportPayload) =>
      downloadPostedFile(`${path(storeId)}/export/xlsx`, payload, 'wprowadzanie.xlsx'),
    meta: { invalidates: false },
  })
}
