import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { documentsKey, suppliersKey } from '@/features/documents/api'
import { api } from '@/lib/api'
import type {
  DocumentImport,
  EanChange,
  ProductImport,
  ProductImportOverview,
  SupplierImport,
  SupplierImportOverview,
} from '@/lib/types'

/** Każdy sklep ma własną kartotekę i historię importów, więc sklep jest częścią klucza. */
const productImportsKey = (storeId: string) => ['stores', storeId, 'imports', 'products'] as const
const productImportsPath = (storeId: string) => `/stores/${storeId}/imports/products`

export function useProductImports(storeId: string) {
  return useQuery({
    queryKey: productImportsKey(storeId),
    queryFn: () => api<ProductImportOverview>(productImportsPath(storeId)),
  })
}

export function useEanChanges(storeId: string, importId: string) {
  return useQuery({
    queryKey: [...productImportsKey(storeId), importId, 'ean-changes'],
    queryFn: () => api<EanChange[]>(`${productImportsPath(storeId)}/${importId}/ean-changes`),
    // Dziennik importu się nie zmienia.
    staleTime: Infinity,
  })
}

export function useImportProducts(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (file: File) => {
      const formData = new FormData()
      formData.append('file', file)
      return api<ProductImport>(productImportsPath(storeId), { method: 'POST', formData })
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: productImportsKey(storeId) }),
  })
}

const documentImportsKey = (storeId: string) => ['stores', storeId, 'imports', 'documents'] as const
const documentImportsPath = (storeId: string) => `/stores/${storeId}/imports/documents`

/** Historia wgrań raportu Wejścia/Wyjścia (dokumenty magazynowe). */
export function useDocumentImports(storeId: string) {
  return useQuery({
    queryKey: documentImportsKey(storeId),
    queryFn: () => api<DocumentImport[]>(documentImportsPath(storeId)),
  })
}

/** Wgranie raportu Wejścia/Wyjścia; po nim odświeżamy historię i moduł Dokumenty (kafelki, listy, otwarte dokumenty). */
export function useImportDocuments(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (file: File) => {
      const formData = new FormData()
      formData.append('file', file)
      return api<DocumentImport>(documentImportsPath(storeId), { method: 'POST', formData })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: documentImportsKey(storeId) })
      queryClient.invalidateQueries({ queryKey: documentsKey(storeId) })
    },
  })
}

const supplierImportsKey = (storeId: string) => ['stores', storeId, 'imports', 'suppliers'] as const
const supplierImportsPath = (storeId: string) => `/stores/${storeId}/imports/suppliers`

/** Liczba dostawców sklepu i historia wgrań raportu "Lista dostawców". */
export function useSupplierImports(storeId: string) {
  return useQuery({
    queryKey: supplierImportsKey(storeId),
    queryFn: () => api<SupplierImportOverview>(supplierImportsPath(storeId)),
  })
}

/** Wgranie listy dostawców; odświeża też dostawców do wyboru przy gratisach. */
export function useImportSuppliers(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (file: File) => {
      const formData = new FormData()
      formData.append('file', file)
      return api<SupplierImport>(supplierImportsPath(storeId), { method: 'POST', formData })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: supplierImportsKey(storeId) })
      queryClient.invalidateQueries({ queryKey: suppliersKey(storeId) })
    },
  })
}
