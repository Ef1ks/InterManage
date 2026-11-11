import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api, ApiError } from './api'
import { withoutLeadingZeros } from './format'
import type {
  ArticleDelivery,
  ArticleProtocol,
  DashboardSettings,
  MySchedule,
  ProductListItem,
  ProductListPage,
  ProductPriceHistory,
  SalesArticle,
  SalesHistory,
  SalesReportsOverview,
} from './types'

const reports = (storeId: string) => `/stores/${storeId}/reports`

export type LookupResult =
  | { kind: 'found'; itm8: string }
  | { kind: 'several'; items: ProductListItem[] }
  | { kind: 'none' }

/**
 * Kod z aparatu albo wpisany: jak pole EAN w Magazynie. Główny EAN ma pierwszeństwo (krótki kod bywa początkiem
 * dłuższych), jeden wynik otwiera się od razu, kilka daje listę do wyboru.
 */
export async function lookupArticle(storeId: string, code: string): Promise<LookupResult> {
  const wanted = code.trim()
  const page = await api<ProductListPage>(`/stores/${storeId}/products?${new URLSearchParams({ query: wanted })}`)
  const exact = page.items.find((item) => withoutLeadingZeros(item.ean) === withoutLeadingZeros(wanted))
  if (exact) return { kind: 'found', itm8: exact.itm8 }
  if (page.total === 1 && page.items[0]) return { kind: 'found', itm8: page.items[0].itm8 }
  if (page.items.length > 0) return { kind: 'several', items: page.items }
  return { kind: 'none' }
}

export function useReportsOverview(storeId: string) {
  return useQuery({
    queryKey: ['stores', storeId, 'reports'],
    queryFn: () => api<SalesReportsOverview>(reports(storeId)),
  })
}

/** Artykuł z kartoteki; `null`, gdy go w niej nie ma. */
export function useArticle(storeId: string, itm8: string) {
  return useQuery({
    queryKey: ['stores', storeId, 'article', itm8],
    queryFn: async () => {
      try {
        return await api<SalesArticle>(`${reports(storeId)}/articles/${itm8}`)
      } catch (error) {
        if (error instanceof ApiError && error.status === 404) return null
        throw error
      }
    },
  })
}

export function useSalesHistory(storeId: string, itm8: string, range: { from: string; to: string } | null) {
  return useQuery({
    queryKey: ['stores', storeId, 'article', itm8, 'history', range],
    queryFn: () => api<SalesHistory>(`${reports(storeId)}/sales-history?${new URLSearchParams({ ...range!, itm8 })}`),
    enabled: range != null,
  })
}

export function useDeliveries(storeId: string, itm8: string) {
  return useQuery({
    queryKey: ['stores', storeId, 'article', itm8, 'deliveries'],
    queryFn: () => api<ArticleDelivery[]>(`${reports(storeId)}/articles/${itm8}/deliveries`),
  })
}

export function useProtocols(storeId: string, itm8: string) {
  return useQuery({
    queryKey: ['stores', storeId, 'article', itm8, 'protocols'],
    queryFn: () => api<ArticleProtocol[]>(`${reports(storeId)}/articles/${itm8}/protocols`),
  })
}

const DASHBOARD_SETTINGS_KEY = ['dashboard-settings'] as const

/** Ustawienia pulpitu konta (co pokazuje wykres wyników sklepu), wspólne z aplikacją w przeglądarce. */
export function useDashboardSettings(enabled = true) {
  return useQuery({
    queryKey: DASHBOARD_SETTINGS_KEY,
    queryFn: () => api<DashboardSettings>('/auth/dashboard-settings'),
    staleTime: Infinity,
    enabled,
  })
}

export function useSaveDashboardSettings() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (settings: DashboardSettings) =>
      api<DashboardSettings>('/auth/dashboard-settings', { method: 'PUT', json: settings }),
    // Wykres przełącza się od razu, a przy błędzie wraca do zapisanego.
    onMutate: (settings) => {
      const previous = queryClient.getQueryData<DashboardSettings>(DASHBOARD_SETTINGS_KEY)
      queryClient.setQueryData(DASHBOARD_SETTINGS_KEY, settings)
      return { previous }
    },
    onError: (_error, _settings, context) => queryClient.setQueryData(DASHBOARD_SETTINGS_KEY, context?.previous),
    onSuccess: (saved) => queryClient.setQueryData(DASHBOARD_SETTINGS_KEY, saved),
  })
}

/** Sprzedaż całego sklepu dzień po dniu (bez artykułu i nomenklatury). */
export function useStoreHistory(storeId: string, range: { from: string; to: string } | null) {
  return useQuery({
    queryKey: ['stores', storeId, 'store-history', range],
    queryFn: () => api<SalesHistory>(`${reports(storeId)}/sales-history?${new URLSearchParams(range!)}`),
    enabled: range != null,
    staleTime: 10 * 60 * 1000,
  })
}

/** Własny grafik zalogowanego pracownika (ostatnie publikacje). */
export function useMySchedule(range: { from: string; to: string }) {
  return useQuery({
    queryKey: ['my-schedule', range],
    queryFn: () => api<MySchedule>(`/me/schedule?${new URLSearchParams(range)}`),
  })
}

export function usePrices(storeId: string, itm8: string) {
  return useQuery({
    queryKey: ['stores', storeId, 'article', itm8, 'prices'],
    queryFn: () => api<ProductPriceHistory>(`/stores/${storeId}/products/${itm8}/prices`),
  })
}
