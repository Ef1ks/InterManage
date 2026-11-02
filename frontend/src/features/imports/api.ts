import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { documentsKey, suppliersKey } from '@/features/documents/api'
import { reportsKey } from '@/features/sales/api'
import { api } from '@/lib/api'
import type {
  DocumentImport,
  EanChange,
  NomenclatureImport,
  NomenclatureImportOverview,
  ProductCodeImport,
  ProductCodeImportOverview,
  ProductImport,
  ProductImportOverview,
  PromotionImport,
  PromotionImportOverview,
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

const promotionImportsKey = (storeId: string) => ['stores', storeId, 'imports', 'promotions'] as const
const promotionImportsPath = (storeId: string) => `/stores/${storeId}/imports/promotions`

/** Ile promocji trwa albo się zacznie i historia wgrań listy promocji (Taryfy.pdf). */
export function usePromotionImports(storeId: string) {
  return useQuery({
    queryKey: promotionImportsKey(storeId),
    queryFn: () => api<PromotionImportOverview>(promotionImportsPath(storeId)),
  })
}

/** Wgranie listy bieżących i przyszłych promocji (PDF, ok. 60 stron czyta się kilka sekund). */
export function useImportPromotions(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (file: File) => {
      const formData = new FormData()
      formData.append('file', file)
      return api<PromotionImport>(promotionImportsPath(storeId), { method: 'POST', formData })
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: promotionImportsKey(storeId) }),
  })
}

const productCodeImportsKey = (storeId: string) => ['stores', storeId, 'imports', 'product-codes'] as const
const productCodeImportsPath = (storeId: string) => `/stores/${storeId}/imports/product-codes`

/** Ile kodów i artykułów jest w najnowszych multikodach i historia ich wgrań. */
export function useProductCodeImports(storeId: string) {
  return useQuery({
    queryKey: productCodeImportsKey(storeId),
    queryFn: () => api<ProductCodeImportOverview>(productCodeImportsPath(storeId)),
  })
}

/** Wgranie multikodów (wszystkie EAN-y przypisane teraz do każdego ITM8). */
export function useImportProductCodes(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (file: File) => {
      const formData = new FormData()
      formData.append('file', file)
      return api<ProductCodeImport>(productCodeImportsPath(storeId), { method: 'POST', formData })
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: productCodeImportsKey(storeId) }),
  })
}
