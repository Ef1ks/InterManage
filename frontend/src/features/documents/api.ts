import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/lib/api'
import type { SortState } from '@/lib/table-sort'
import type { Document, DocumentPage, DocumentsOverview, Supplier } from '@/lib/types'

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
