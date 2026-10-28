import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import type { DateRange, Periods } from '@/features/sales/periods'
import { api, ApiError, downloadFile, printFile } from '@/lib/api'
import type {
  DeliCostsOverview,
  NomenclatureOption,
  PeriodSummary,
  RegionBudget,
  SalesArticle,
  SalesComparison,
  SalesHistory,
  SalesReportsOverview,
} from '@/lib/types'

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
 * Porównanie sprzedaży albo koszty garmażu: ten sam widok, ale koszty garmażu liczą tylko rejon 36, a w miejsce
 * zakupu FIFO mają wartość protokołów reprezentacji (koszt i marża tylko przy całym garmażu).
 */
export type ReportKind = 'sales' | 'deli'

/** Skąd są koszty garmażu: najnowszy raport dokumentów (protokoły reprezentacji) i do kiedy sięgają. */
export function useDeliCostsOverview(storeId: string, enabled: boolean) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'deli-costs'],
    queryFn: () => api<DeliCostsOverview>(`${reportsPath(storeId)}/deli-costs`),
    enabled,
  })
}

/** Cała nomenklatura sklepu (ok. 2,5 tys. pozycji) do filtra raportów. */
export function useSalesNomenclatures(storeId: string) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'nomenclatures'],
    queryFn: () => api<NomenclatureOption[]>(`${reportsPath(storeId)}/nomenclatures`),
    staleTime: 5 * 60_000,
  })
}

/**
 * Jeden poziom porównania sprzedaży: bez {@code parent} rejony, z kodem rejonu jego rodziny, z kodem rodziny
 * podrodziny, a z kodem podrodziny artykuły. {@code unassigned} to artykuły bez nomenklatury. {@code filter} to
 * wybrane pozycje nomenklatury (pusty = cały sklep).
 */
export const salesComparisonKey = (
  storeId: string,
  kind: ReportKind,
  periods: Periods,
  filter: string[],
  parent: string,
  unassigned = false,
) => [...reportsKey(storeId), `${kind}-comparison`, periods, filter, parent, unassigned] as const

export function useSalesComparison(
  storeId: string,
  kind: ReportKind,
  periods: Periods,
  filter: string[],
  parent: string,
  unassigned = false,
) {
  return useQuery({
    queryKey: salesComparisonKey(storeId, kind, periods, filter, parent, unassigned),
    queryFn: () => {
      const params = new URLSearchParams({
        previousFrom: periods.previous.from,
        previousTo: periods.previous.to,
        currentFrom: periods.current.from,
        currentTo: periods.current.to,
        parent,
      })
      if (unassigned) params.set('unassigned', 'true')
      filter.forEach((code) => params.append('nomenclatures', code))
      return api<SalesComparison>(`${reportsPath(storeId)}/${kind}-comparison?${params}`)
    },
    // Po zmianie okresu tabela nie znika, tylko podmienia liczby po wczytaniu.
    placeholderData: keepPreviousData,
  })
}

/** Czego wykres: artykułu albo pozycji nomenklatury ({@code code} pusty = cały sklep, {@code unassigned} = bez nomenklatury). */
export type SalesChartTarget =
  | {
      kind: 'article'
      itm8: string
      name: string | null
      ean: string | null
      brand: string | null
    }
  | {
      kind: 'group'
      code: string
      unassigned: boolean
      name: string
      levelLabel: string
    }

/** Sprzedaż dzień po dniu do wykresu; grupa z tym samym filtrem nomenklatury co tabela, żeby się zgadzały. */
export function useSalesHistory(
  storeId: string,
  kind: ReportKind,
  target: SalesChartTarget,
  range: DateRange,
  filter: string[],
) {
  return useQuery({
    queryKey: [...reportsKey(storeId), `${kind}-history`, target.kind === 'article' ? target.itm8 : target, range, filter],
    queryFn: () => {
      const params = new URLSearchParams({ from: range.from, to: range.to })
      if (target.kind === 'article') {
        params.set('itm8', target.itm8)
      } else {
        params.set('parent', target.code)
        if (target.unassigned) params.set('unassigned', 'true')
        filter.forEach((code) => params.append('nomenclatures', code))
      }
      return api<SalesHistory>(`${reportsPath(storeId)}/${kind}-history?${params}`)
    },
    placeholderData: keepPreviousData,
  })
}

/** Artykuł z kartoteki; {@code null}, gdy go w niej nie ma (sprzedaż artykułu spoza kartoteki). */
export function useSalesArticle(storeId: string, itm8: string | null) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'article', itm8],
    queryFn: async () => {
      try {
        return await api<SalesArticle>(`${reportsPath(storeId)}/articles/${itm8}`)
      } catch (error) {
        if (error instanceof ApiError && error.status === 404) return null
        throw error
      }
    },
    enabled: itm8 != null,
  })
}

/** Podsumowanie sprzedaży i strat za okres (rejony w grupach z budżetem). */
export function usePeriodSummary(storeId: string, range: DateRange, enabled: boolean) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'period-summary', range],
    queryFn: () =>
      api<PeriodSummary>(`${reportsPath(storeId)}/period-summary?${new URLSearchParams({ from: range.from, to: range.to })}`),
    enabled,
    placeholderData: keepPreviousData,
  })
}

const summaryPdfPath = (storeId: string, range: DateRange) =>
  `${reportsPath(storeId)}/period-summary/pdf?${new URLSearchParams({ from: range.from, to: range.to })}`

/** Wydruk podsumowania: PDF od razu w oknie drukowania albo zapis pliku. */
export function usePeriodSummaryPdf(storeId: string) {
  return {
    print: useMutation({
      mutationFn: (range: DateRange) => printFile(summaryPdfPath(storeId, range)),
      meta: { invalidates: false },
    }),
    download: useMutation({
      mutationFn: (range: DateRange) => downloadFile(summaryPdfPath(storeId, range), 'podsumowanie.pdf'),
      meta: { invalidates: false },
    }),
  }
}

/** Budżet rejonów (docelowa marża i max straty) z ustawień sklepu. */
export function useRegionBudgets(storeId: string, enabled = true) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'region-budgets'],
    queryFn: () => api<RegionBudget[]>(`${reportsPath(storeId)}/region-budgets`),
    enabled,
  })
}

export function useSaveRegionBudgets(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (budgets: { regionCode: string; marginPercent: number | null; maxLossPercent: number | null }[]) =>
      api<RegionBudget[]>(`${reportsPath(storeId)}/region-budgets`, { method: 'PUT', json: { budgets } }),
    onSuccess: (budgets) => queryClient.setQueryData([...reportsKey(storeId), 'region-budgets'], budgets),
  })
}
