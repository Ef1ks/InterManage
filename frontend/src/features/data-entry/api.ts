import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type { SortState } from '@/lib/table-sort'
import type {
  DataEntryCatalogArticle,
  DataEntryCatalogListPage,
  CorrectionDelivery,
  CorrectionDeliveryLine,
  DataEntryCatalogProduct,
  DataEntryInvoice,
  DataEntryInvoiceItem,
  DataEntryInvoicePage,
  DataEntrySkippedSupplier,
  DataEntryXmlDocument,
  DataEntryXmlExport,
  InvoiceBulkResult,
  InvoiceMarkKind,
  KsefInvoice,
  KsefInvoicePage,
  SupplierRuleMode,
  NomenclatureItem,
  PriceControlFileItem,
  SuggestionResponse,
  SuggestionStatus,
} from '@/lib/types'

import { parseNumber } from './grid-values'

const path = (storeId: string) => `/stores/${storeId}/data-entry`

/**
 * Wczytana faktura z listy faktur KSeF (po numerze i id): czy jest już w systemie sklepu (`deliveries`: przyjęcie z jej
 * numerem albo przypięty dokument w sklepie firmy) i czy to korekta. `null`, gdy jej nie widać na liście.
 */
export function useLoadedKsefInvoice(storeId: string, invoiceId: string | null, number: string | null) {
  return useQuery({
    queryKey: ['stores', storeId, 'ksef', 'loaded-invoice', invoiceId, number],
    queryFn: async () => {
      const params = new URLSearchParams({ query: number ?? '', showSkipped: 'true' })
      const page = await api<KsefInvoicePage>(`/stores/${storeId}/ksef/invoices?${params}`)
      return page.invoices.find((invoice: KsefInvoice) => invoice.id === invoiceId) ?? null
    },
    enabled: Boolean(invoiceId && number),
  })
}

/**
 * Wolne kody 240001-242999 do zakładania artykułów ważonych (pełne EAN-y, np. 2400010000000), bez zajętych w kartotece,
 * multikodach i szkicach artykułów. Pobierane przy każdym otwarciu okna, bo kartoteka zmienia się w ciągu dnia.
 */
export function useFreeScaleCodes(storeId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['stores', storeId, 'data-entry', 'free-scale-codes'],
    queryFn: () => api<string[]>(`${path(storeId)}/free-scale-codes`),
    enabled,
    staleTime: 0,
  })
}

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
    queryFn: () => api<DataEntryInvoice>(`${path(storeId)}/invoices/${invoiceId}?source=${encodeURIComponent(source)}`),
    enabled: invoiceId !== null,
    // Przy zmianie źródła tej samej faktury poprzedni wynik zostaje na ekranie, przy innej fakturze - nie.
    placeholderData: (previous) => (previous?.invoiceId === invoiceId ? previous : undefined),
    staleTime: 60_000,
    retry: false,
  })
}

/** Bony do faktury korygującej: najpierw faktur korygowanych, potem pasujące do `query` (bez niego dostawcy korekty). */
export function useCorrectionDeliveries(storeId: string, invoiceId: string | null, query: string, enabled: boolean) {
  return useQuery({
    queryKey: ['stores', storeId, 'data-entry', 'correction-deliveries', invoiceId, query],
    queryFn: () =>
      api<CorrectionDelivery[]>(
        `${path(storeId)}/invoices/${invoiceId}/correction-deliveries?query=${encodeURIComponent(query)}`,
      ),
    enabled: enabled && invoiceId !== null,
    placeholderData: keepPreviousData,
  })
}

/** Linie bonu po wcześniejszych korektach; `documentId` puste = bon spoza raportów albo jeszcze nie potrzebne. */
export function useCorrectionDeliveryLines(storeId: string, invoiceId: string | null, documentId: string | null) {
  return useQuery({
    queryKey: ['stores', storeId, 'data-entry', 'correction-delivery-lines', invoiceId, documentId],
    queryFn: () =>
      api<CorrectionDeliveryLine[]>(
        `${path(storeId)}/invoices/${invoiceId}/correction-deliveries/${documentId}/lines`,
      ),
    enabled: invoiceId !== null && documentId !== null,
  })
}

/** 100 ostatnich plików XML zapisanych w sklepie przez „Zapisz XML”, od najnowszego. */
export function useXmlHistory(storeId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['stores', storeId, 'data-entry', 'xml-history'],
    queryFn: () => api<DataEntryXmlExport[]>(`${path(storeId)}/xml-history`),
    enabled,
  })
}

const temporaryKey = (storeId: string) => ['stores', storeId, 'data-entry', 'xml-temporary'] as const

/** Zapisy tymczasowe sklepu (najwyżej 10), od najnowszego. */
export function useTemporaryXml(storeId: string, enabled: boolean) {
  return useQuery({
    queryKey: temporaryKey(storeId),
    queryFn: () => api<DataEntryXmlExport[]>(`${path(storeId)}/xml-temporary`),
    enabled,
  })
}

/** „Zapisz tymczasowo”: plik XML z tabeli i cała tabela (`draft`, JSON), bez zapisu na dysku. */
export function useSaveTemporaryXml(storeId: string) {
  return useMutation({
    mutationFn: (body: { export: ExportPayload; draft: string }) =>
      api<DataEntryXmlExport>(`${path(storeId)}/xml-temporary`, { method: 'POST', json: body }),
  })
}

/** Usunięcie zapisu tymczasowego (znacznik) i „Przywróć”; obie zwracają listę po zmianie. */
export function useTemporaryXmlChange(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, restore }: { id: string; restore: boolean }) =>
      api<DataEntryXmlExport[]>(`${path(storeId)}/xml-temporary/${id}${restore ? '/restore' : ''}`, {
        method: restore ? 'POST' : 'DELETE',
      }),
    onSuccess: (list) => queryClient.setQueryData(temporaryKey(storeId), list),
    meta: { invalidates: false },
  })
}

/** Plik z historii albo zapis tymczasowy jako dokument do tabeli. */
export function fetchXmlHistoryDocument(storeId: string, id: string) {
  return api<DataEntryXmlDocument>(`${path(storeId)}/xml-history/${id}`)
}

export const xmlHistoryFilePath = (storeId: string, id: string) => `${path(storeId)}/xml-history/${id}/file`

/** Plik XML faktury z dysku jako dokument do tabeli (nic nie zapisuje). */
export function readXmlFile(storeId: string, file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return api<DataEntryXmlDocument>(`${path(storeId)}/xml-documents`, { method: 'POST', formData })
}

/** Filtry okna wczytywania faktur (zapamiętane w przeglądarce, bez wyszukiwania). */
export type InvoicePickerFilters = {
  kind: 'INVOICES' | 'CORRECTIONS'
  /** NEW: do wczytania, DONE: wczytane, pobrane i nie moje, ALL: wszystkie. */
  status: 'NEW' | 'DONE' | 'ALL'
  /** ALL: IDWew sklepu i bez IDWew (domyślnie), OWN: tylko IDWew sklepu, NONE: tylko bez IDWew. */
  internalId: 'ALL' | 'OWN' | 'NONE'
  showSkipped: boolean
}

const invoicesKey = (storeId: string) => ['stores', storeId, 'data-entry', 'invoices'] as const

/** Faktury albo korekty do wczytania, strony po 100 (kolejne przy przewijaniu), najnowsze na górze. */
export function useDataEntryInvoices(storeId: string, filters: InvoicePickerFilters, query: string) {
  return useInfiniteQuery({
    queryKey: [...invoicesKey(storeId), filters, query],
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({
        page: String(pageParam),
        kind: filters.kind,
        status: filters.status,
        internalId: filters.internalId,
        showSkipped: String(filters.showSkipped),
      })
      if (query) params.set('query', query)
      return api<DataEntryInvoicePage>(`${path(storeId)}/invoices?${params}`)
    },
    initialPageParam: 0,
    getNextPageParam: (last, pages) => (last.hasMore ? pages.length : undefined),
    placeholderData: keepPreviousData,
  })
}

/** Lista w oknie wczytywania po zmianie statusu faktury (znacznik, pobranie, wydruk). */
export function useRefreshDataEntryInvoices(storeId: string) {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: invoicesKey(storeId) })
}

/** Znacznik faktury (wczytana, nie moja) albo powrót na listę "Do wczytania" ({@code kind} = null). */
export function useMarkInvoice(storeId: string) {
  const refresh = useRefreshDataEntryInvoices(storeId)
  return useMutation({
    mutationFn: ({ invoiceId, kind }: { invoiceId: string; kind: InvoiceMarkKind | null }) =>
      kind
        ? api<DataEntryInvoiceItem>(`${path(storeId)}/invoices/${invoiceId}/marks`, { method: 'POST', json: { kind } })
        : api<DataEntryInvoiceItem>(`${path(storeId)}/invoices/${invoiceId}/marks`, { method: 'DELETE' }),
    onSuccess: () => refresh(),
    meta: { invalidates: false },
  })
}

export type InvoiceBulkAction = 'LOADED' | 'NOT_MINE' | 'RESTORE'

/** Zaznaczone faktury: podane albo wszystkie pasujące do filtrów listy bez wykluczonych. */
export type InvoiceBulkSelection =
  { invoiceIds: string[] } | { filter: InvoicePickerFilters & { query: string }; excludeIds: string[] }

/** Działanie na zaznaczonych fakturach (wczytane, nie moje, z powrotem do wczytania) i jego „Cofnij”. */
export function useBulkInvoices(storeId: string) {
  const refresh = useRefreshDataEntryInvoices(storeId)
  const run = useMutation({
    mutationFn: ({ action, selection }: { action: InvoiceBulkAction; selection: InvoiceBulkSelection }) =>
      api<InvoiceBulkResult>(`${path(storeId)}/invoices/bulk`, { method: 'POST', json: { action, ...selection } }),
    onSuccess: () => refresh(),
    meta: { invalidates: false },
  })
  const undo = useMutation({
    mutationFn: (result: InvoiceBulkResult) =>
      api<void>(`${path(storeId)}/invoices/bulk/undo`, {
        method: 'POST',
        json: { createdMarkIds: result.createdMarkIds, deletedMarkIds: result.deletedMarkIds },
      }),
    onSuccess: () => refresh(),
    meta: { invalidates: false },
  })
  return { run, undo }
}

/** Wydruk faktury: podstawowy (pozycje, opis, dokumenty WZ) albo pełna faktura. */
export type InvoicePrintVariant = 'BASIC' | 'FULL'

export const invoicePdfPath = (storeId: string, invoiceId: string, variant: InvoicePrintVariant) =>
  `${path(storeId)}/invoices/${invoiceId}/pdf?variant=${variant}`

const skippedKey = (storeId: string) => ['stores', storeId, 'data-entry', 'skipped-suppliers'] as const

export function useSkippedSuppliers(storeId: string) {
  return useQuery({
    queryKey: skippedKey(storeId),
    queryFn: () => api<DataEntrySkippedSupplier[]>(`${path(storeId)}/skipped-suppliers`),
  })
}

/** Pomijanie dostawcy w oknie wczytywania: dodanie, usunięcie z listy (znacznik) i przywrócenie. */
export function useSkipSupplier(storeId: string) {
  const queryClient = useQueryClient()
  const refresh = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: skippedKey(storeId) }),
      queryClient.invalidateQueries({ queryKey: invoicesKey(storeId) }),
    ])
  return useMutation({
    mutationFn: (
      action:
        | { type: 'skip'; sellerNip: string; sellerName: string | null; mode: SupplierRuleMode }
        | { type: 'unskip'; id: string }
        | { type: 'restore'; id: string },
    ) =>
      action.type === 'skip'
        ? api<DataEntrySkippedSupplier>(`${path(storeId)}/skipped-suppliers`, {
            method: 'POST',
            json: { sellerNip: action.sellerNip, sellerName: action.sellerName, mode: action.mode },
          })
        : action.type === 'unskip'
          ? api<void>(`${path(storeId)}/skipped-suppliers/${action.id}`, { method: 'DELETE' }).then(() => null)
          : api<DataEntrySkippedSupplier>(`${path(storeId)}/skipped-suppliers/${action.id}/restore`, {
              method: 'POST',
            }),
    onSuccess: () => refresh(),
    meta: { invalidates: false },
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
 * (początek kodu podrodziny) albo nazwa poziomu; dostawca to kod albo fragment nazwy dostawcy z przyjęć PZ z ostatnich
 * 6 miesięcy; ceny od-do jak wpisane (błędna liczba jest pomijana).
 */
export type CatalogListFilters = {
  /** Pole „Szukaj”: każde słowo w nazwie, marce, EAN-ie albo ITM8. */
  query: string
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
  'itm8' | 'ean' | 'name' | 'brand' | 'nomenclature' | 'purchase' | 'sale' | 'stock' | 'rotation'

/** Lista towarów z kartoteki, strony po 100 (kolejne przy przewijaniu), posortowana w bazie (puste na końcu). */
export function useCatalogList(
  storeId: string,
  filters: CatalogListFilters,
  sort: SortState<CatalogSortKey>,
  enabled: boolean,
) {
  return useInfiniteQuery({
    queryKey: ['stores', storeId, 'data-entry', 'catalog', filters, sort],
    queryFn: ({ pageParam }) => fetchCatalogPage(storeId, filters, sort, pageParam),
    initialPageParam: 0,
    getNextPageParam: (last, pages) => (last.hasMore ? pages.length : undefined),
    placeholderData: keepPreviousData,
    enabled,
  })
}

/** Jedna strona listy towarów (100 artykułów); też do pobrania wszystkich wyfiltrowanych naraz. */
export function fetchCatalogPage(
  storeId: string,
  filters: CatalogListFilters,
  sort: SortState<CatalogSortKey>,
  page: number,
) {
  const params = new URLSearchParams({ page: String(page) })
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
      return api<DataEntryCatalogArticle[]>(`${path(storeId)}/link-suggestions?${params}`)
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
  /** Bez faktury z KSeF: NIP i data z dokumentu wczytanego z pliku XML. */
  sellerNip: string | null
  issueDate: string | null
  /** Dostawca z listy sklepu wybrany zamiast sprzedawcy z dokumentu. */
  supplierId: string | null
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

/**
 * Nowe i zmienione artykuły do pliku CADBEZP (.xls) dla programu sklepu, zapisywanego razem z plikiem XML. Dostawca
 * i numer tylko do nazwy pliku; {@code purchasePrice} to cena zakupu netto sztuki sprzedaży.
 */
/** Artykuły z kartoteki ze zmienioną w tabeli ceną sprzedaży do pliku CTRLPRIX (dostawca jak w pliku XML). */
export type PriceControlPayload = {
  supplierId: string | null
  sellerNip: string | null
  invoiceNumber: string | null
  rows: { ean: string; salePrice: number }[]
}

const priceControlKey = (storeId: string) => ['stores', storeId, 'data-entry', 'price-control-files'] as const

/** 10 ostatnich plików CTRLPRIX sklepu (do ponownego zapisu). */
export function usePriceControlFiles(storeId: string, enabled: boolean) {
  return useQuery({
    queryKey: priceControlKey(storeId),
    queryFn: () => api<PriceControlFileItem[]>(`${path(storeId)}/price-control-files`),
    enabled,
  })
}

export type ProductFilePayload = {
  supplierId: string | null
  sellerNip: string | null
  sellerName: string | null
  invoiceNumber: string | null
  rows: {
    ean: string | null
    name: string
    brand: string | null
    unit: string
    netContent: number
    purchasePrice: number | null
    vatPercent: number | null
    salePrice: number | null
    subfamilyCode: string | null
    supplierCode: string | null
    cn: string | null
    /** Sztuk w kartonie (PAK z dostawy z bazy) do Opak.Bazy i Zamówienia minimalnego; bez niego 1. */
    pack?: number | null
  }[]
}
