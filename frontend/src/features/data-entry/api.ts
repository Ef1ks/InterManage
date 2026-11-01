import { keepPreviousData, useInfiniteQuery, useMutation, useQuery } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type { SortState } from '@/lib/table-sort'
import type {
  DataEntryCatalogListItem,
  DataEntryCatalogListPage,
  DataEntryCatalogProduct,
  DataEntryInvoice,
  NomenclatureItem,
  SuggestionResponse,
  SuggestionStatus,
} from '@/lib/types'

import { parseNumber } from './grid-values'

const path = (storeId: string) => `/stores/${storeId}/data-entry`

/**
 * Cała nomenklatura sklepu: nazwy rejonów, rodzin i podrodzin w tabeli, wyszukiwarka podrodzin i marża wymagana do
 * ceny sugerowanej. Marżę zmienia się zwykle w Ustawieniach sklepu w drugiej karcie, więc powrót do karty z tabelą
 * zawsze pobiera ją od nowa i ceny sugerowane od razu się przeliczają.
 */
export function useDataEntryNomenclature(storeId: string) {
  return useQuery({
    queryKey: ['stores', storeId, 'data-entry', 'nomenclatures'],
    queryFn: () => api<NomenclatureItem[]>(`${path(storeId)}/nomenclatures`),
    refetchOnWindowFocus: 'always',
  })
}

/**
 * Pozycje faktury z KSeF z artykułami z kartoteki, EAN z wybranego źródła (AUTO, GTIN, INDEX, EXTRA:klucz, NAME).
 * Za pierwszym razem serwer pobiera XML z KSeF, potem bierze go z bazy.
 */
export function useInvoiceLines(storeId: string, invoiceId: string | null, source: string) {
  return useQuery({
    queryKey: ['stores', storeId, 'data-entry', 'invoice', invoiceId, source],
    queryFn: () =>
      api<DataEntryInvoice>(`${path(storeId)}/invoices/${invoiceId}?source=${encodeURIComponent(source)}`),
    enabled: invoiceId !== null,
    // Przy zmianie źródła tej samej faktury poprzedni wynik zostaje na ekranie, przy innej fakturze - nie.
    placeholderData: (previous) => (previous?.invoiceId === invoiceId ? previous : undefined),
    staleTime: 60_000,
    retry: false,
  })
}

/** Artykuły z kartoteki dla wpisanych albo wklejonych EAN-ów. */
export function lookupProducts(storeId: string, eans: string[]) {
  const params = new URLSearchParams()
  eans.forEach((ean) => params.append('ean', ean))
  return api<DataEntryCatalogProduct[]>(`${path(storeId)}/products?${params}`)
}

/**
 * Filtry nad kolumnami listy towarów: w tekstach `*` = dowolny ciąg znaków, `?` = jeden znak; nomenklatura to kod
 * (początek kodu podrodziny) albo nazwa poziomu; ceny od-do jak wpisane (błędna liczba jest pomijana).
 */
export type CatalogListFilters = {
  itm8: string
  ean: string
  name: string
  brand: string
  supplier: string
  nomenclature: string
  purchaseFrom: string
  purchaseTo: string
  saleFrom: string
  saleTo: string
}

const PRICE_FILTERS = new Set(['purchaseFrom', 'purchaseTo', 'saleFrom', 'saleTo'])

/** Kolumny, po których serwer sortuje listę towarów. */
export type CatalogSortKey =
  | 'itm8'
  | 'ean'
  | 'name'
  | 'brand'
  | 'supplier'
  | 'nomenclature'
  | 'purchase'
  | 'sale'
  | 'stock'

/** Lista towarów z kartoteki, strony po 100 (kolejne przy przewijaniu), posortowana w bazie (puste na końcu). */
export function useCatalogList(
  storeId: string,
  filters: CatalogListFilters,
  sort: SortState<CatalogSortKey>,
  enabled: boolean,
) {
  return useInfiniteQuery({
    queryKey: ['stores', storeId, 'data-entry', 'catalog', filters, sort],
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({ page: String(pageParam) })
      Object.entries(filters).forEach(([key, value]) => {
        if (!PRICE_FILTERS.has(key)) {
          if (value.trim()) params.set(key, value.trim())
          return
        }
        const price = parseNumber(value)
        if (price !== null && price !== undefined) params.set(key, String(price))
      })
      params.set('sort', sort.key)
      params.set('direction', sort.direction)
      return api<DataEntryCatalogListPage>(`${path(storeId)}/catalog?${params}`)
    },
    initialPageParam: 0,
    getNextPageParam: (last, pages) => (last.hasMore ? pages.length : undefined),
    placeholderData: keepPreviousData,
    enabled,
  })
}

/**
 * Propozycje artykułów do przypięcia pozycji bez EAN-u: podobne nazwy z kartoteki (pg_trgm i początki słów, bo
 * kartoteka skraca nazwy), najpierw towar dostawcy o tym NIP-ie.
 */
export function useLinkSuggestions(storeId: string, text: string, sellerNip: string | null, enabled: boolean) {
  return useQuery({
    queryKey: ['stores', storeId, 'data-entry', 'link-suggestions', text, sellerNip],
    queryFn: () => {
      const params = new URLSearchParams({ text })
      if (sellerNip) params.set('sellerNip', sellerNip)
      return api<DataEntryCatalogListItem[]>(`${path(storeId)}/link-suggestions?${params}`)
    },
    enabled: enabled && text.trim() !== '',
  })
}

export type ProductLinkResponse = { id: string; code: string; product: DataEntryCatalogProduct }

/** Przypięcie kodu pozycji dostawcy do artykułu; tabela sama podmienia swoje wiersze. */
export function useLinkProduct(storeId: string) {
  return useMutation({
    mutationFn: (payload: { sellerNip: string; code: string; itm8: string }) =>
      api<ProductLinkResponse>(`${path(storeId)}/links`, { method: 'POST', json: payload }),
    meta: { invalidates: false },
  })
}

/** Odpięcie (znacznik): kolejne wczytanie faktury pokaże pozycję bez artykułu. */
export function useUnlinkProduct(storeId: string) {
  return useMutation({
    mutationFn: (linkId: string) => api<void>(`${path(storeId)}/links/${linkId}`, { method: 'DELETE' }),
    meta: { invalidates: false },
  })
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

/** Tabela do pliku XML faktury; numer, data i NIP w pliku idą z faktury KSeF `invoiceId`. */
export type ExportPayload = {
  invoiceId: string | null
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
    cadencierPrice: number | null
    subfamilyCode: string | null
    unit: string
    netContent: number
    status: SuggestionStatus | null
    known: boolean
  }[]
}
