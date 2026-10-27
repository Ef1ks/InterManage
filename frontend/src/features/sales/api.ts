import { keepPreviousData, useQuery } from '@tanstack/react-query'

import type { Periods } from '@/features/sales/periods'
import { api } from '@/lib/api'
import type { SalesComparison, SalesReportsOverview } from '@/lib/types'

/** Raporty liczą się z zapisanej sprzedaży sklepu, więc sklep jest częścią klucza. */
export const reportsKey = (storeId: string) => ['stores', storeId, 'reports'] as const
const reportsPath = (storeId: string) => `/stores/${storeId}/reports`

/** Za jakie dni jest sprzedaż i z kiedy jest najnowszy raport. */
export function useSalesReportsOverview(storeId: string) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'overview'],
    queryFn: () => api<SalesReportsOverview>(reportsPath(storeId)),
  })
}

/**
 * Jeden poziom porównania sprzedaży: bez {@code parent} rejony, z kodem rejonu jego rodziny, z kodem rodziny
 * podrodziny, a z kodem podrodziny artykuły. {@code unassigned} to artykuły bez nomenklatury.
 */
export const salesComparisonKey = (storeId: string, periods: Periods, parent: string, unassigned = false) =>
  [...reportsKey(storeId), 'sales-comparison', periods, parent, unassigned] as const

export function useSalesComparison(storeId: string, periods: Periods, parent: string, unassigned = false) {
  return useQuery({
    queryKey: salesComparisonKey(storeId, periods, parent, unassigned),
    queryFn: () => {
      const params = new URLSearchParams({
        previousFrom: periods.previous.from,
        previousTo: periods.previous.to,
        currentFrom: periods.current.from,
        currentTo: periods.current.to,
        parent,
      })
      if (unassigned) params.set('unassigned', 'true')
      return api<SalesComparison>(`${reportsPath(storeId)}/sales-comparison?${params}`)
    },
    // Po zmianie okresu tabela nie znika, tylko podmienia liczby po wczytaniu.
    placeholderData: keepPreviousData,
  })
}
