import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { documentsKey, suppliersKey } from '@/features/documents/api'
import { reportsKey } from '@/features/sales/api'
import { api } from '@/lib/api'
import type {
  DocumentImport,
  EanChange,
  ImportGuide,
  ImportHistoryPage,
  ImportReportKind,
  NomenclatureImport,
  NomenclatureImportOverview,
  ProductCodeImport,
  ProductCodeImportOverview,
  ProductImport,
  ProductImportJob,
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
const IMPORT_POLL_MS = 2000

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

const productImportJobKey = (storeId: string) => [...productImportsKey(storeId), 'job'] as const

/** Postęp importu: najpierw wysyłanie pliku (`UPLOADING`, liczone w przeglądarce), potem etapy zadania na serwerze. */
export type ProductImportProgress = Pick<ProductImportJob, 'percent' | 'processedRows' | 'totalRows'> & {
  phase: ProductImportJob['phase'] | 'UPLOADING'
}

/** Stan trwającego importu (postęp), wpisywany przez {@link useImportProducts} przy każdym pytaniu o zadanie. */
export function useProductImportProgress(storeId: string) {
  return useQuery<ProductImportProgress | null>({
    queryKey: productImportJobKey(storeId),
    queryFn: () => null,
    enabled: false,
    staleTime: Infinity,
  }).data
}

export function useImportProducts(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    // Duży raport scala się kilka minut, a Cloudflare zrywa żądanie po 100 s: import idzie w tle i pytamy o stan.
    mutationFn: async (file: File): Promise<ProductImport> => {
      const formData = new FormData()
      formData.append('file', file)
      const setProgress = (progress: ProductImportProgress | null) =>
        queryClient.setQueryData(productImportJobKey(storeId), progress)
      const uploading = (percent: number) =>
        setProgress({ phase: 'UPLOADING', percent, processedRows: 0, totalRows: 0 })
      try {
        // Duży plik wysyła się dłużej niż trwa odczyt, więc pasek rusza od razu, a nie dopiero po przyjęciu pliku.
        uploading(0)
        let job = await api<ProductImportJob>(productImportsPath(storeId), {
          method: 'POST',
          formData,
          onUploadProgress: uploading,
        })
        setProgress(job)
        while (job.status === 'RUNNING') {
          await new Promise((resolve) => setTimeout(resolve, IMPORT_POLL_MS))
          job = await api<ProductImportJob>(`${productImportsPath(storeId)}/jobs/${job.id}`)
          setProgress(job)
        }
        if (!job.result) throw new Error('Import nie zwrócił wyniku. Odśwież stronę i sprawdź historię importów.')
        return job.result
      } finally {
        setProgress(null)
      }
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

/**
 * Wspólna historia importów wszystkich raportów, porcjami z serwera (po 30, od najnowszego); kolejną porcję
 * wyznacza chwila wgrania ostatniej pozycji. Każde wgranie unieważnia wszystkie zapytania, więc lista się odświeża.
 */
export function useImportHistory(storeId: string) {
  return useInfiniteQuery({
    queryKey: ['stores', storeId, 'imports', 'history'],
    queryFn: ({ pageParam }) => {
      const params = pageParam ? `?${new URLSearchParams({ before: pageParam })}` : ''
      return api<ImportHistoryPage>(`/stores/${storeId}/imports/history${params}`)
    },
    initialPageParam: null as string | null,
    getNextPageParam: (last) => (last.hasMore ? (last.items.at(-1)?.importedAt ?? null) : null),
  })
}

/** Instrukcje do raportów są wspólne dla wszystkich sklepów. */
const importGuidesKey = ['import-guides'] as const

export function useImportGuides() {
  return useQuery({
    queryKey: importGuidesKey,
    queryFn: () => api<ImportGuide[]>('/import-guides'),
  })
}

/** Zmiana instrukcji (tylko właściciel); pusta treść i ścieżka usuwają ją. */
export function useSaveImportGuide() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ kind, ...values }: { kind: ImportReportKind; content: string; folderPath: string }) =>
      api<ImportGuide[]>(`/settings/import-guides/${kind}`, { method: 'PUT', json: values }),
    onSuccess: (guides) => queryClient.setQueryData(importGuidesKey, guides),
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

/** Dni objęte wgranymi raportami sprzedaży i dokumentów (połączone okresy): z dziur makro drukuje zaległe raporty. */
export type ImportCoverage = {
  sales: { from: string; to: string }[]
  documents: { from: string; to: string }[]
}

export function useImportCoverage(storeId: string) {
  return useQuery({
    queryKey: ['stores', storeId, 'imports', 'coverage'],
    queryFn: () => api<ImportCoverage>(`/stores/${storeId}/imports/coverage`),
  })
}
