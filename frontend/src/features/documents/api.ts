import { useMemo } from 'react'
import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { ksefKey } from '@/features/ksef/api'
import { api, downloadFile } from '@/lib/api'
import type { SortState } from '@/lib/table-sort'
import type {
  AutoInvoiceMatch,
  CorrectionCandidate,
  DeliveryKsefCorrections,
  DeliveryReportClosing,
  DeliveryReportClosingsOverview,
  Document,
  DocumentPage,
  DocumentsOverview,
  GratisKind,
  GratisSupplierSuggestion,
  IncomingTransfer,
  IncomingTransfers,
  InvoiceComparison,
  KsefInvoiceCandidate,
  Supplier,
} from '@/lib/types'
import { useDebouncedValue } from '@/lib/use-debounced-value'

/** Dokumenty są osobne dla każdego sklepu, więc sklep jest częścią klucza. Raport wgrywa się w Imporcie danych. */
export const documentsKey = (storeId: string) => ['stores', storeId, 'documents'] as const
const documentsPath = (storeId: string) => `/stores/${storeId}/documents`

/** Kafelki typów z liczbą dokumentów. */
export function useDocumentsOverview(storeId: string) {
  return useQuery({
    queryKey: [...documentsKey(storeId), 'overview'],
    queryFn: () => api<DocumentsOverview>(documentsPath(storeId)),
  })
}

export type DocumentColumn = 'number' | 'date' | 'supplier' | 'supplierDocument' | 'lines' | 'purchase' | 'sale'

/** Ma / nie ma (faktury, korekt); `all` = bez filtra. */
export type Presence = 'all' | 'with' | 'without'

/** Gratisy: oznaczone, nieoznaczone albo `candidate` - nieoznaczone z pozycją GRATIS (mogą być gratisem). */
export type GratisFilter = Presence | 'candidate'

/** Kwota, po której działa ukrywanie zakresu: zakup albo sprzedaż brutto. */
export type ValueField = 'purchase' | 'sale'

/** Dokumenty z kwotą w przedziale [from, to] są ukryte; `null` = granica otwarta, obie `null` = bez filtra. */
export type HiddenValueRange = { field: ValueField; from: number | null; to: number | null }

/**
 * Wyszukiwanie i filtry listy: {@code query} w numerze, dostawcy i nr dok. dostawcy, {@code product} w liniach
 * (nazwa, marka, EAN, ITM8). {@code invoice} działa w przyjęciach i korektach od dostawcy, {@code corrections}
 * i {@code closed} (w zamkniętym raporcie okresowym) w przyjęciach, {@code gratis} w przeniesieniach stanów.
 * {@code hiddenValues}: zakres kwot do ukrycia. {@code hiddenSuppliers}: kody dostawców, których dokumentów lista
 * nie pokazuje.
 */
export type DocumentFilters = {
  query: string
  product: string
  invoice: Presence
  corrections: Presence
  closed: Presence
  gratis: GratisFilter
  hiddenValues: HiddenValueRange
  hiddenSuppliers: string[]
}

/**
 * Lista dokumentów typu stronami po 100 (wyszukiwanie, filtry i sortowanie w bazie); kolejne strony dociąga
 * {@code fetchNextPage} przy przewijaniu. {@code typeCode} jak w ścieżce strony ("pz", "pl"...).
 */
export function useDocuments(storeId: string, typeCode: string, filters: DocumentFilters, sort: SortState<DocumentColumn>) {
  return useInfiniteQuery({
    queryKey: [...documentsKey(storeId), typeCode, 'list', { ...filters, ...sort }],
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({ page: String(pageParam), sort: sort.key, direction: sort.direction })
      if (filters.query) params.set('query', filters.query)
      if (filters.product) params.set('product', filters.product)
      if (filters.invoice !== 'all') params.set('invoice', filters.invoice)
      if (filters.corrections !== 'all') params.set('corrections', filters.corrections)
      if (filters.closed !== 'all') params.set('closed', filters.closed)
      if (filters.gratis !== 'all') params.set('gratis', filters.gratis)
      const { field, from, to } = filters.hiddenValues
      if (from !== null || to !== null) {
        params.set('hideValueOf', field)
        if (from !== null) params.set('hideValueFrom', String(from))
        if (to !== null) params.set('hideValueTo', String(to))
      }
      filters.hiddenSuppliers.forEach((code) => params.append('hiddenSuppliers', code))
      return api<DocumentPage>(`${documentsPath(storeId)}/${typeCode}?${params}`)
    },
    initialPageParam: 0,
    getNextPageParam: (last) => (last.hasMore ? last.page + 1 : undefined),
    // Przy zmianie wyszukiwania albo sortowania lista nie znika, tylko podmienia się po wczytaniu.
    placeholderData: keepPreviousData,
  })
}

/** Propozycje automatycznego przypięcia faktur KSeF do przyjęć bez faktury; pobierane po otwarciu okna. */
export function useAutoInvoiceMatches(storeId: string, enabled: boolean) {
  return useQuery({
    queryKey: [...documentsKey(storeId), 'pz', 'auto-ksef-invoices'],
    queryFn: () => api<AutoInvoiceMatch[]>(`${documentsPath(storeId)}/pz/auto-ksef-invoices`),
    enabled,
    staleTime: 0,
  })
}

export type AutoInvoicePair = { documentId: string; invoiceId: string }

/** Przypięcie zaakceptowanych propozycji (zwraca liczbę przypiętych) i jego cofnięcie. */
export function useAutoInvoiceLinks(storeId: string) {
  const path = `${documentsPath(storeId)}/pz/auto-ksef-invoices`
  return {
    link: useMutation({
      mutationFn: (matches: AutoInvoicePair[]) => api<number>(path, { method: 'POST', json: { matches } }),
    }),
    undo: useMutation({
      mutationFn: (matches: AutoInvoicePair[]) => api<void>(`${path}/undo`, { method: 'POST', json: { matches } }),
    }),
  }
}

export function useDocument(storeId: string, typeCode: string, id: string) {
  return useQuery({
    queryKey: [...documentsKey(storeId), typeCode, id],
    queryFn: () => api<Document>(`${documentsPath(storeId)}/${typeCode}/${id}`),
  })
}

/**
 * Rejestracja przeniesienia stanów jako gratisu: każda zmiana zwraca cały dokument, który od razu podmieniamy,
 * a listy dokumentów odświeżamy (pokazują, od kogo jest gratis).
 */
export function useDocumentGratis(storeId: string, typeCode: string, id: string) {
  const queryClient = useQueryClient()
  const path = `${documentsPath(storeId)}/${typeCode}/${id}/gratis`
  const onSuccess = (document: Document) => {
    queryClient.setQueryData([...documentsKey(storeId), typeCode, id], document)
    queryClient.invalidateQueries({ queryKey: [...documentsKey(storeId), typeCode, 'list'] })
  }
  return {
    register: useMutation({
      mutationFn: (request: RegisterGratis) => api<Document>(path, { method: 'PUT', json: request }),
      onSuccess,
    }),
    cancel: useMutation({ mutationFn: () => api<Document>(path, { method: 'DELETE' }), onSuccess }),
    restore: useMutation({ mutationFn: () => api<Document>(`${path}/restore`, { method: 'POST' }), onSuccess }),
  }
}

/** {@code reportMonth} (RRRR-MM) pusty = miesiąc daty dokumentu. */
export type RegisterGratis = { supplierId: string; kind: GratisKind; reportMonth: string | null }

/** Ostatni dostawcy artykułów z dokumentu (z przyjęć PZ), do wyboru dostawcy gratisu. */
export function useGratisSupplierSuggestions(storeId: string, typeCode: string, id: string) {
  return useQuery({
    queryKey: [...documentsKey(storeId), typeCode, id, 'gratis-suppliers'],
    queryFn: () =>
      api<GratisSupplierSuggestion[]>(`${documentsPath(storeId)}/${typeCode}/${id}/gratis/supplier-suggestions`),
  })
}

/**
 * Poprawka numeru dokumentu dostawcy w przyjęciu PZ i jej cofnięcie. Po tym numerze faktury KSeF wiążą się z dostawą,
 * więc odświeżamy też listę faktur.
 */
export function useSupplierDocumentNumber(storeId: string, typeCode: string, id: string) {
  const queryClient = useQueryClient()
  const path = `${documentsPath(storeId)}/${typeCode}/${id}/supplier-document-number`
  const onSuccess = (document: Document) => {
    queryClient.setQueryData([...documentsKey(storeId), typeCode, id], document)
    queryClient.invalidateQueries({ queryKey: [...documentsKey(storeId), typeCode, 'list'] })
    queryClient.invalidateQueries({ queryKey: ksefKey(storeId) })
  }
  return {
    save: useMutation({
      mutationFn: (supplierDocumentNumber: string) =>
        api<Document>(path, { method: 'PUT', json: { supplierDocumentNumber } }),
      onSuccess,
    }),
    restore: useMutation({ mutationFn: () => api<Document>(path, { method: 'DELETE' }), onSuccess }),
  }
}

/**
 * Faktury KSeF do przypięcia dokumentu: domyślnie z kwotą netto ±10% wartości dokumentu, od najbliższej;
 * {@code query} szuka po numerze faktury, dostawcy i NIP-ie.
 */
export function useKsefInvoiceCandidates(
  storeId: string,
  typeCode: string,
  id: string,
  query: string,
  amountRange: boolean,
  enabled: boolean,
) {
  const debounced = useDebouncedValue(query.trim(), 250)
  return useQuery({
    queryKey: [...documentsKey(storeId), typeCode, id, 'ksef-candidates', { query: debounced, amountRange }],
    queryFn: () => {
      const params = new URLSearchParams({ amountRange: String(amountRange) })
      if (debounced) params.set('query', debounced)
      return api<KsefInvoiceCandidate[]>(`${documentsPath(storeId)}/${typeCode}/${id}/ksef-invoices/candidates?${params}`)
    },
    enabled,
    placeholderData: keepPreviousData,
  })
}

/**
 * Przypięcie faktury KSeF do dokumentu i odpięcie. Zmienia status faktury („wprowadzona”), więc odświeżamy też listę
 * faktur.
 */
export function useDocumentKsefLinks(storeId: string, typeCode: string, id: string) {
  const queryClient = useQueryClient()
  const path = `${documentsPath(storeId)}/${typeCode}/${id}/ksef-invoices`
  const onSuccess = (document: Document) => {
    queryClient.setQueryData([...documentsKey(storeId), typeCode, id], document)
    queryClient.invalidateQueries({ queryKey: [...documentsKey(storeId), typeCode, 'list'] })
    queryClient.invalidateQueries({ queryKey: [...documentsKey(storeId), typeCode, id, 'ksef-candidates'] })
    queryClient.invalidateQueries({ queryKey: ksefKey(storeId) })
  }
  return {
    link: useMutation({
      mutationFn: (invoiceId: string) => api<Document>(path, { method: 'POST', json: { invoiceId } }),
      onSuccess,
    }),
    unlink: useMutation({
      mutationFn: (invoiceId: string) => api<Document>(`${path}/${invoiceId}`, { method: 'DELETE' }),
      onSuccess,
    }),
  }
}

/** Adres pliku faktury spoza KSeF (podgląd, pobranie, wydruk). */
export const manualInvoiceFilePath = (storeId: string, typeCode: string, documentId: string, invoiceId: string) =>
  `${documentsPath(storeId)}/${typeCode.toLowerCase()}/${documentId}/manual-invoices/${invoiceId}/file`

/**
 * Faktury spoza KSeF dokumentu: dopisanie, usunięcie (znacznik) i przywrócenie, a także plik faktury (dołączenie
 * albo wymiana, usunięcie i przywrócenie). Każda zmiana zwraca dokument.
 */
export function useManualInvoices(storeId: string, typeCode: string, id: string) {
  const queryClient = useQueryClient()
  const path = `${documentsPath(storeId)}/${typeCode}/${id}/manual-invoices`
  const onSuccess = (document: Document) => {
    queryClient.setQueryData([...documentsKey(storeId), typeCode, id], document)
    queryClient.invalidateQueries({ queryKey: [...documentsKey(storeId), typeCode, 'list'] })
  }
  return {
    add: useMutation({
      mutationFn: (request: { supplierId: string; invoiceNumber: string; issueDate: string | null }) =>
        api<Document>(path, { method: 'POST', json: request }),
      onSuccess,
    }),
    remove: useMutation({
      mutationFn: (invoiceId: string) => api<Document>(`${path}/${invoiceId}`, { method: 'DELETE' }),
      onSuccess,
    }),
    restore: useMutation({
      mutationFn: (invoiceId: string) => api<Document>(`${path}/${invoiceId}/restore`, { method: 'POST' }),
      onSuccess,
    }),
    attachFile: useMutation({
      mutationFn: ({ invoiceId, file }: { invoiceId: string; file: File }) => {
        const formData = new FormData()
        formData.append('file', file)
        return api<Document>(`${path}/${invoiceId}/file`, { method: 'PUT', formData })
      },
      onSuccess,
    }),
    removeFile: useMutation({
      mutationFn: (invoiceId: string) => api<Document>(`${path}/${invoiceId}/file`, { method: 'DELETE' }),
      onSuccess,
    }),
    restoreFile: useMutation({
      mutationFn: (invoiceId: string) => api<Document>(`${path}/${invoiceId}/file/restore`, { method: 'POST' }),
      onSuccess,
    }),
  }
}

/**
 * Do przyjęcia: jego korekty do przypięcia; do korekty: przyjęcia, których może dotyczyć. Zawsze od tego samego
 * dostawcy, najpierw z największą liczbą wspólnych artykułów.
 */
export function useCorrectionCandidates(storeId: string, typeCode: string, id: string, query: string, enabled: boolean) {
  const debounced = useDebouncedValue(query.trim(), 250)
  return useQuery({
    queryKey: [...documentsKey(storeId), typeCode, id, 'correction-candidates', { query: debounced }],
    queryFn: () => {
      const params = new URLSearchParams()
      if (debounced) params.set('query', debounced)
      return api<CorrectionCandidate[]>(`${documentsPath(storeId)}/${typeCode}/${id}/corrections/candidates?${params}`)
    },
    enabled,
    placeholderData: keepPreviousData,
  })
}

/**
 * Przypięcie korekty do przyjęcia i odpięcie, z dowolnej strony ({@code otherId}: druga strona). Zmienia oba dokumenty
 * i listy, a faktury korekt od dostawcy pojawiają się przy przyjęciu, więc odświeżamy cały moduł.
 */
export function useDeliveryCorrections(storeId: string, typeCode: string, id: string) {
  const queryClient = useQueryClient()
  const path = `${documentsPath(storeId)}/${typeCode}/${id}/corrections`
  const onSuccess = (document: Document) => {
    queryClient.setQueryData([...documentsKey(storeId), typeCode, id], document)
    queryClient.invalidateQueries({ queryKey: documentsKey(storeId) })
  }
  return {
    attach: useMutation({
      mutationFn: (otherId: string) => api<Document>(path, { method: 'POST', json: { documentId: otherId } }),
      onSuccess,
    }),
    detach: useMutation({
      mutationFn: (otherId: string) => api<Document>(`${path}/${otherId}`, { method: 'DELETE' }),
      onSuccess,
    }),
  }
}

/**
 * Przyjęcie porównane z XML faktur pozycja po pozycji (po EAN). XML pobiera się z KSeF tylko za pierwszym razem
 * (limit pobrań), więc przy błędzie nie ponawiamy.
 */
export function useInvoiceComparison(storeId: string, id: string, enabled: boolean) {
  return useQuery({
    queryKey: [...documentsKey(storeId), 'pz', id, 'invoice-comparison'],
    queryFn: () => api<InvoiceComparison>(`${documentsPath(storeId)}/pz/${id}/invoice-comparison`),
    enabled,
    retry: false,
    staleTime: 5 * 60_000,
  })
}

/** Korekty z KSeF faktury przyjęcia PZ z podpowiedziami korekt od dostawcy (WZ). */
export function useDeliveryKsefCorrections(storeId: string, id: string, enabled: boolean) {
  return useQuery({
    queryKey: [...documentsKey(storeId), 'pz', id, 'ksef-corrections'],
    queryFn: () => api<DeliveryKsefCorrections>(`${documentsPath(storeId)}/pz/${id}/ksef-corrections`),
    enabled,
  })
}

/**
 * Przypina korektę z KSeF do korekty od dostawcy (WZ) i WZ do przyjęcia; `attached` mówi, czy WZ przypięto teraz
 * (cofnięcie odpina ją wtedy od przyjęcia).
 */
export function useDeliveryKsefCorrectionLinks(storeId: string, id: string) {
  const path = `${documentsPath(storeId)}/pz/${id}/ksef-corrections`
  return {
    link: useMutation({
      mutationFn: ({ invoiceId, documentId }: { invoiceId: string; documentId: string }) =>
        api<{ attached: boolean }>(`${path}/${invoiceId}/document`, { method: 'POST', json: { documentId } }),
    }),
    unlink: useMutation({
      mutationFn: ({ invoiceId, documentId, detach }: { invoiceId: string; documentId: string; detach: boolean }) =>
        api<void>(`${path}/${invoiceId}/document/${documentId}?detach=${detach}`, { method: 'DELETE' }),
    }),
  }
}

/**
 * Ręczne łączenie pozycji faktury z artykułami przyjęcia w porównaniu (inny kod u dostawcy, pozycja rozbita na kilka)
 * oraz rozłączanie i przywracanie (po kilka połączeń naraz, bo wiersz może składać się z kilku).
 */
export function useInvoiceComparisonMatches(storeId: string, id: string) {
  const path = `${documentsPath(storeId)}/pz/${id}/invoice-comparison/matches`
  return {
    match: useMutation({
      mutationFn: (request: { invoiceKeys: string[]; itm8s: string[] }) =>
        api<{ id: string }>(path, { method: 'POST', json: request }),
    }),
    unmatch: useMutation({
      mutationFn: (matchIds: string[]) =>
        Promise.all(matchIds.map((matchId) => api<void>(`${path}/${matchId}`, { method: 'DELETE' }))),
    }),
    restore: useMutation({
      mutationFn: (matchIds: string[]) =>
        Promise.all(matchIds.map((matchId) => api<void>(`${path}/${matchId}/restore`, { method: 'POST' }))),
    }),
  }
}

/** Potwierdzenie różnicy kwot przyjęcia i faktury oraz jego cofnięcie. */
export function useAmountConfirmation(storeId: string, typeCode: string, id: string) {
  const queryClient = useQueryClient()
  const path = `${documentsPath(storeId)}/${typeCode}/${id}/amount-confirmation`
  const onSuccess = (document: Document) => queryClient.setQueryData([...documentsKey(storeId), typeCode, id], document)
  return {
    confirm: useMutation({ mutationFn: () => api<Document>(path, { method: 'PUT' }), onSuccess }),
    cancel: useMutation({ mutationFn: () => api<Document>(path, { method: 'DELETE' }), onSuccess }),
  }
}

/** Wydruk bonu dostawy przyjęcia (PDF z wartościami po korektach). */
export function useDownloadDeliveryNote(storeId: string) {
  return useMutation({
    mutationFn: (document: Pick<Document, 'id' | 'number'>) =>
      downloadFile(`${documentsPath(storeId)}/pz/${document.id}/delivery-note`, `bon-dostawy-${document.number}.pdf`),
    meta: { invalidates: false },
  })
}

/**
 * Podgląd raportu przyjęć za okres (Excel, nic nie zapisuje): przyjęcie z bonem, fakturą, dostawcą i korektami, pod nim
 * zakup netto po korektach według rejonów nomenklatury, a na końcu zmiany w innych zamkniętych okresach. Daty
 * RRRR-MM-DD, oba dni włącznie.
 */
export function useDownloadDeliveryReport(storeId: string) {
  return useMutation({
    mutationFn: ({ from, to }: { from: string; to: string }) =>
      downloadFile(
        `${documentsPath(storeId)}/pz/period-report?${new URLSearchParams({ from, to })}`,
        `przyjecia-${from}-${to}.xlsx`,
      ),
    meta: { invalidates: false },
  })
}

const closingsKey = (storeId: string) => [...documentsKey(storeId), 'pz', 'report-closings'] as const
const closingsPath = (storeId: string) => `${documentsPath(storeId)}/pz/report-closings`

/** Zamknięte okresy raportu przyjęć i liczba przyjęć zmienionych po zamknięciu. */
export function useDeliveryReportClosings(storeId: string) {
  return useQuery({
    queryKey: closingsKey(storeId),
    queryFn: () => api<DeliveryReportClosingsOverview>(closingsPath(storeId)),
  })
}

/**
 * Zapisuje pełną listę kodów dostawców pomijanych w raporcie przyjęć. Odpowiedź to cały przegląd zamknięć (liczba
 * zmian zależy od listy), więc podmienia go bez unieważniania; kolejne zmiany idą po kolei.
 */
export function useSetExcludedSuppliers(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (supplierCodes: string[]) =>
      api<DeliveryReportClosingsOverview>(`${documentsPath(storeId)}/pz/report-excluded-suppliers`, {
        method: 'PUT',
        json: { supplierCodes },
      }),
    onSuccess: (overview) => queryClient.setQueryData(closingsKey(storeId), overview),
    scope: { id: `delivery-report-excluded-suppliers-${storeId}` },
    meta: { invalidates: false },
  })
}

const downloadClosed = (storeId: string, closing: DeliveryReportClosing) =>
  downloadFile(
    `${closingsPath(storeId)}/${closing.id}/xlsx`,
    `przyjecia-${closing.periodFrom}-${closing.periodTo}-zamkniety.xlsx`,
  )

/** Zamknięty raport tak, jak poszedł (późniejsze zmiany go nie ruszają). */
export function useDownloadClosedReport(storeId: string) {
  return useMutation({
    mutationFn: (closing: DeliveryReportClosing) => downloadClosed(storeId, closing),
    meta: { invalidates: false },
  })
}

/** Zamyka okres i od razu pobiera zamknięty raport. */
export function useCloseDeliveryReport(storeId: string) {
  return useMutation({
    mutationFn: async (period: { from: string; to: string }) => {
      const closing = await api<DeliveryReportClosing>(closingsPath(storeId), { method: 'POST', json: period })
      await downloadClosed(storeId, closing)
      return closing
    },
  })
}

/** Cofnięcie ostatniego zamknięcia (znacznik) i jego przywrócenie. */
export function useDeliveryReportClosingActions(storeId: string) {
  return {
    cancel: useMutation({
      mutationFn: (id: string) => api<DeliveryReportClosing>(`${closingsPath(storeId)}/${id}`, { method: 'DELETE' }),
    }),
    restore: useMutation({
      mutationFn: (id: string) =>
        api<DeliveryReportClosing>(`${closingsPath(storeId)}/${id}/restore`, { method: 'POST' }),
    }),
  }
}

/** Same zmiany przyjęć z zamkniętych okresów, które czekają na następny raport. */
export function useDownloadPendingChanges(storeId: string) {
  return useMutation({
    mutationFn: () =>
      downloadFile(`${documentsPath(storeId)}/pz/period-report/changes`, 'przyjecia-zmiany-po-zamknieciu.xlsx'),
    meta: { invalidates: false },
  })
}

/** Dostawcy sklepu (z listy z systemu sklepu i dodani ręcznie). Jest ich kilkuset, więc filtruje przeglądarka. */
export const suppliersKey = (storeId: string) => ['stores', storeId, 'suppliers'] as const

export function useSuppliers(storeId: string) {
  return useQuery({
    queryKey: suppliersKey(storeId),
    queryFn: () => api<Supplier[]>(`/stores/${storeId}/suppliers`),
  })
}

/** Podpowiadany dostawca z listy sklepu po kodzie (np. dostawca linii dokumentu). */
export function useSuggestedSupplier(storeId: string, code: string | null) {
  const { data: suppliers } = useSuppliers(storeId)
  return useMemo(
    () => (code ? (suppliers?.find((supplier) => supplier.code === code) ?? null) : null),
    [suppliers, code],
  )
}

/** Dostawca spoza systemu sklepu - jest tylko w aplikacji. */
export function useCreateSupplier(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: { name: string; nip: string }) =>
      api<Supplier>(`/stores/${storeId}/suppliers`, { method: 'POST', json: request }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: suppliersKey(storeId) }),
  })
}

/**
 * Sklep zaprzyjaźniony, dla którego wystawiono ruch międzymagazynowy: wskazanie, zmiana, cofnięcie (znacznik)
 * i przywrócenie. Każda zmiana zwraca cały dokument, który od razu podmieniamy.
 */
export function useTransferTarget(storeId: string, typeCode: string, id: string) {
  const queryClient = useQueryClient()
  const path = `${documentsPath(storeId)}/${typeCode}/${id}/transfer-target`
  const onSuccess = (document: Document) => queryClient.setQueryData([...documentsKey(storeId), typeCode, id], document)
  return {
    set: useMutation({
      mutationFn: (targetStoreId: string) => api<Document>(path, { method: 'PUT', json: { storeId: targetStoreId } }),
      onSuccess,
    }),
    cancel: useMutation({ mutationFn: () => api<Document>(path, { method: 'DELETE' }), onSuccess }),
    restore: useMutation({ mutationFn: () => api<Document>(`${path}/restore`, { method: 'POST' }), onSuccess }),
  }
}

/** Ruchy międzymagazynowe innych sklepów wystawione dla tego sklepu: do wprowadzenia i ostatnio wprowadzone. */
const incomingKey = (storeId: string) => ['stores', storeId, 'incoming-transfers'] as const
const incomingPath = (storeId: string) => `/stores/${storeId}/incoming-transfers`

export function useIncomingTransfers(storeId: string) {
  return useQuery({
    queryKey: [...incomingKey(storeId), 'list'],
    queryFn: () => api<IncomingTransfers>(incomingPath(storeId)),
  })
}

/** Licznik w menu; odświeża się co minutę, bo dokument wystawia inny sklep. */
export function useIncomingTransfersCount(storeId: string, enabled: boolean) {
  return useQuery({
    queryKey: [...incomingKey(storeId), 'count'],
    queryFn: () => api<number>(`${incomingPath(storeId)}/count`),
    enabled,
    refetchInterval: 60_000,
  })
}

export function useIncomingTransfer(storeId: string, id: string) {
  return useQuery({
    queryKey: [...incomingKey(storeId), id],
    queryFn: () => api<IncomingTransfer>(`${incomingPath(storeId)}/${id}`),
  })
}

/** Oznacza dokument jako wprowadzony w tym sklepie albo cofa oznaczenie. */
export function useMarkTransferEntered(storeId: string, id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (entered: boolean) =>
      api<IncomingTransfer>(`${incomingPath(storeId)}/${id}/entered`, { method: 'PUT', json: { entered } }),
    onSuccess: (transfer) => queryClient.setQueryData([...incomingKey(storeId), id], transfer),
  })
}

/** Ilości, które naprawdę przyjechały; zapis oznacza dokument jako wprowadzony. */
export function useReceiveTransfer(storeId: string, id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (items: { lineId: string; quantity: number }[]) =>
      api<IncomingTransfer>(`${incomingPath(storeId)}/${id}/receive`, { method: 'POST', json: { items } }),
    onSuccess: (transfer) => queryClient.setQueryData([...incomingKey(storeId), id], transfer),
  })
}

/** Plik przyjęcia w przyjętych ilościach: PROTO6 (.Traite0) dla programu sklepu albo Excel. */
export const incomingTransferExportPath = (storeId: string, id: string, format: 'proto' | 'xlsx') =>
  `${incomingPath(storeId)}/${id}/export/${format}`
