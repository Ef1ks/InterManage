import { keepPreviousData, useQuery } from '@tanstack/react-query'

import { api } from './api'
import type { DateRange, Periods } from './periods'
import type {
  CounterReport,
  CounterStoreOption,
  PeriodSummary,
  PeriodSummaryLevel,
  ProfitTargetProgress,
  SalesComparison,
  SalesPromotionShare,
  StockGapReport,
} from './types'

// Zapytania raportów sprzedaży, te same endpointy co `features/sales/api.ts` w przeglądarce.

const reports = (storeId: string) => `/stores/${storeId}/reports`

/** Raporty liczą się do kilku sekund, a dane zmienia tylko import, więc trzymamy je dłużej (jak REPORT_STALE_TIME). */
const REPORT_STALE_TIME = 10 * 60 * 1000

const periodParams = (periods: Periods) => ({
  previousFrom: periods.previous.from,
  previousTo: periods.previous.to,
  currentFrom: periods.current.from,
  currentTo: periods.current.to,
})

/**
 * Jeden poziom porównania: bez `parent` rejony, z kodem rejonu rodziny, z kodem rodziny podrodziny, z kodem podrodziny
 * artykuły; `unassigned` = artykuły bez nomenklatury.
 */
export function useSalesComparison(storeId: string, periods: Periods | null, parent = '', unassigned = false) {
  return useQuery({
    queryKey: ['stores', storeId, 'reports', 'sales-comparison', periods, parent, unassigned],
    staleTime: REPORT_STALE_TIME,
    placeholderData: keepPreviousData,
    enabled: periods != null,
    queryFn: () => {
      const params = new URLSearchParams({ ...periodParams(periods!), parent })
      if (unassigned) params.set('unassigned', 'true')
      return api<SalesComparison>(`${reports(storeId)}/sales-comparison?${params}`)
    },
  })
}

/** Sprzedaż z dni promocji w obu okresach (osobne zapytanie, bo liczy się dłużej niż tabela). */
export function useSalesPromotionShare(storeId: string, periods: Periods | null) {
  return useQuery({
    queryKey: ['stores', storeId, 'reports', 'sales-promotions', periods],
    staleTime: REPORT_STALE_TIME,
    enabled: periods != null,
    queryFn: () =>
      api<SalesPromotionShare>(
        `${reports(storeId)}/sales-comparison/promotions?${new URLSearchParams(periodParams(periods!))}`,
      ),
  })
}

export function usePeriodSummary(storeId: string, range: DateRange | null) {
  return useQuery({
    queryKey: ['stores', storeId, 'reports', 'period-summary', range],
    staleTime: REPORT_STALE_TIME,
    placeholderData: keepPreviousData,
    enabled: range != null,
    queryFn: () => api<PeriodSummary>(`${reports(storeId)}/period-summary?${new URLSearchParams(range!)}`),
  })
}

export function usePeriodSummaryChildren(
  storeId: string,
  range: DateRange | null,
  parent: string,
  unassigned: boolean,
) {
  return useQuery({
    queryKey: ['stores', storeId, 'reports', 'period-summary', range, 'children', parent, unassigned],
    staleTime: REPORT_STALE_TIME,
    placeholderData: keepPreviousData,
    enabled: range != null,
    queryFn: () =>
      api<PeriodSummaryLevel>(
        `${reports(storeId)}/period-summary/children?${new URLSearchParams({
          ...range!,
          parent,
          unassigned: String(unassigned),
        })}`,
      ),
  })
}

/** Ile z rocznego celu marży osiągnięto od 1 stycznia do `to`. */
export function useProfitTargetProgress(storeId: string, to: string | null) {
  return useQuery({
    queryKey: ['stores', storeId, 'reports', 'profit-targets', 'progress', to],
    staleTime: REPORT_STALE_TIME,
    enabled: to != null,
    queryFn: () =>
      api<ProfitTargetProgress>(`${reports(storeId)}/profit-targets/progress?${new URLSearchParams({ to: to! })}`),
  })
}

export function useCounterStores(storeId: string) {
  return useQuery({
    queryKey: ['stores', storeId, 'reports', 'counter', 'stores'],
    queryFn: () => api<CounterStoreOption[]>(`${reports(storeId)}/counter/stores`),
  })
}

export function useCounterReport(storeId: string, range: DateRange | null, regions: string[], stores: string[]) {
  return useQuery({
    queryKey: ['stores', storeId, 'reports', 'counter', range, regions, stores],
    staleTime: REPORT_STALE_TIME,
    placeholderData: keepPreviousData,
    enabled: range != null && regions.length > 0,
    queryFn: () => {
      const params = new URLSearchParams(range!)
      regions.forEach((code) => params.append('regions', code))
      stores.forEach((id) => params.append('stores', id))
      return api<CounterReport>(`${reports(storeId)}/counter?${params}`)
    },
  })
}

/** Braki towaru z domyślnymi progami z przeglądarki (typowy tydzień od 3 szt., stan do 1, bez dostawy 14 dni). */
export function useStockGaps(storeId: string) {
  return useQuery({
    queryKey: ['stores', storeId, 'reports', 'stock-gaps'],
    staleTime: REPORT_STALE_TIME,
    queryFn: () =>
      api<StockGapReport>(
        `${reports(storeId)}/stock-gaps?${new URLSearchParams({
          minWeekly: '3',
          maxStock: '1',
          noDeliveryDays: '14',
        })}`,
      ),
  })
}
