import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { documentsKey, suppliersKey } from '@/features/documents/api'
import { reportsKey } from '@/features/sales/api'
import { api } from '@/lib/api'
import type {
  DocumentImport,
  EanChange,
  NomenclatureImport,
  NomenclatureImportOverview,
  ProductImport,
  ProductImportOverview,
  SalesImport,
  SalesImportOverview,
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

const salesImportsKey = (storeId: string) => ['stores', storeId, 'imports', 'sales'] as const
const salesImportsPath = (storeId: string) => `/stores/${storeId}/imports/sales`

/** Ile sprzedaży ma sklep, za jakie dni, i historia wgrań raportu sprzedaży. */
export function useSalesImports(storeId: string) {
  return useQuery({
    queryKey: salesImportsKey(storeId),
    queryFn: () => api<SalesImportOverview>(salesImportsPath(storeId)),
  })
}

/** Wgranie raportu sprzedaży (dopisuje tylko nowe dni); plik za pół roku (ok. 500 tys. wierszy) to ok. 20 s. */
export function useImportSales(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (file: File) => {
      const formData = new FormData()
      formData.append('file', file)
      return api<SalesImport>(salesImportsPath(storeId), { method: 'POST', formData })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: salesImportsKey(storeId) })
      queryClient.invalidateQueries({ queryKey: reportsKey(storeId) })
    },
  })
}

const nomenclatureImportsKey = (storeId: string) => ['stores', storeId, 'imports', 'nomenclatures'] as const
const nomenclatureImportsPath = (storeId: string) => `/stores/${storeId}/imports/nomenclatures`

/** Ile rejonów, rodzin i podrodzin ma sklep i historia wgrań listy nomenklatur. */
export function useNomenclatureImports(storeId: string) {
  return useQuery({
    queryKey: nomenclatureImportsKey(storeId),
    queryFn: () => api<NomenclatureImportOverview>(nomenclatureImportsPath(storeId)),
  })
}

/** Wgranie listy nomenklatur (PDF); raporty sprzedaży biorą z niej nazwy, więc też je odświeżamy. */
export function useImportNomenclatures(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (file: File) => {
      const formData = new FormData()
      formData.append('file', file)
      return api<NomenclatureImport>(nomenclatureImportsPath(storeId), { method: 'POST', formData })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: nomenclatureImportsKey(storeId) })
      queryClient.invalidateQueries({ queryKey: reportsKey(storeId) })
    },
  })
}
