import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { ksefKey } from '@/features/ksef/api'
import { api } from '@/lib/api'
import type { SortState } from '@/lib/table-sort'
import type { Document, DocumentPage, DocumentsOverview, KsefInvoiceCandidate, Supplier } from '@/lib/types'
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

/**
 * Lista dokumentów typu stronami po 100 (wyszukiwanie i sortowanie w bazie); kolejne strony dociąga
 * {@code fetchNextPage} przy przewijaniu. {@code typeCode} jak w ścieżce strony ("pz", "pl"...).
 */
export function useDocuments(storeId: string, typeCode: string, query: string, sort: SortState<DocumentColumn>) {
  return useInfiniteQuery({
    queryKey: [...documentsKey(storeId), typeCode, 'list', { query, ...sort }],
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({ page: String(pageParam), sort: sort.key, direction: sort.direction })
      if (query) params.set('query', query)
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
