import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { ksefKey } from '@/features/ksef/api'
import { api, downloadFile } from '@/lib/api'
import type { SortState } from '@/lib/table-sort'
import type {
  CorrectionCandidate,
  DeliveryReportClosing,
  DeliveryReportClosingsOverview,
  Document,
  DocumentPage,
  DocumentsOverview,
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

/**
 * Wyszukiwanie i filtry listy: {@code query} w numerze, dostawcy i nr dok. dostawcy, {@code product} w liniach
 * (nazwa, marka, EAN, ITM8). {@code invoice} działa w przyjęciach i korektach od dostawcy, {@code corrections}
 * w przyjęciach.
 */
export type DocumentFilters = { query: string; product: string; invoice: Presence; corrections: Presence }

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
      return api<DocumentPage>(`${documentsPath(storeId)}/${typeCode}?${params}`)
    },
    initialPageParam: 0,
    getNextPageParam: (last) => (last.hasMore ? last.page + 1 : undefined),
    // Przy zmianie wyszukiwania albo sortowania lista nie znika, tylko podmienia się po wczytaniu.
    placeholderData: keepPreviousData,
  })
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
      mutationFn: (supplierId: string) => api<Document>(path, { method: 'PUT', json: { supplierId } }),
      onSuccess,
    }),
    cancel: useMutation({ mutationFn: () => api<Document>(path, { method: 'DELETE' }), onSuccess }),
    restore: useMutation({ mutationFn: () => api<Document>(`${path}/restore`, { method: 'POST' }), onSuccess }),
  }
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

/** Dostawca spoza systemu sklepu - jest tylko w aplikacji. */
export function useCreateSupplier(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: { name: string; nip: string }) =>
      api<Supplier>(`/stores/${storeId}/suppliers`, { method: 'POST', json: request }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: suppliersKey(storeId) }),
  })
}
