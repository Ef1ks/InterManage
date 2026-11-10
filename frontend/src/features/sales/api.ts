import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import type { DateRange, Periods } from '@/features/sales/periods'
import { api, ApiError, downloadFile, downloadPostedFile, printFile, printPostedFile } from '@/lib/api'
import type {
  ArticleCodes,
  ArticleDelivery,
  ArticleProtocol,
  CadencierImport,
  CadencierOverview,
  CounterMetric,
  CounterReport,
  CounterStoreOption,
  DeliCostsOverview,
  GratisReport,
  IncidentImport,
  IncidentOverview,
  NomenclatureOption,
  PeriodSummary,
  PeriodSummaryLevel,
  LowMarginReport,
  OverpaymentReport,
  PriceCheckCategory,
  PriceCheckReport,
  PriceCheckReview,
  PriceCheckSettings,
  PriceCheckStatus,
  PromotionReport,
  ProtocolKind,
  ProtocolReport,
  SupplierTurnoverReport,
  TurnoverSupplier,
  ProtocolReportGrouping,
  ProtocolReportsOverview,
  RegionBudget,
  SalesArticle,
  SalesComparison,
  SalesHistory,
  SalesReportsOverview,
  TopSalesLevel,
  TopSalesMetric,
  TopSalesPartner,
  TopSalesReport,
  SlowMovingExcludedItem,
  SlowMovingReport,
  StockGapReport,
  NegativeStockDelivery,
  NegativeStockReport,
  ZeroStockReport,
} from '@/lib/types'

/** Raporty liczą się z zapisanej sprzedaży sklepu, więc sklep jest częścią klucza. */
export const reportsKey = (storeId: string) => ['stores', storeId, 'reports'] as const
/**
 * Raporty liczą się z importów sprzedaży i dokumentów; import (jak każda mutacja) unieważnia wszystkie zapytania, więc
 * wynik może zostać świeży dłużej: powrót do raportu albo poprzedniego okresu nie liczy go od nowa w bazie.
 */
const REPORT_STALE_TIME = 10 * 60_000

const reportsPath = (storeId: string) => `/stores/${storeId}/reports`

/** Za jakie dni jest sprzedaż i z kiedy jest najnowszy raport. */
export function useSalesReportsOverview(storeId: string, enabled = true) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'overview'],
    queryFn: () => api<SalesReportsOverview>(reportsPath(storeId)),
    enabled,
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
 * wybrane pozycje nomenklatury (pusty = cały sklep). {@code compareStore} dokłada aktualną sprzedaż drugiego sklepu.
 */
export const salesComparisonKey = (
  storeId: string,
  kind: ReportKind,
  periods: Periods,
  filter: string[],
  parent: string,
  unassigned = false,
  compareStore: string | null = null,
) => [...reportsKey(storeId), `${kind}-comparison`, periods, filter, parent, unassigned, compareStore] as const

export function useSalesComparison(
  storeId: string,
  kind: ReportKind,
  periods: Periods,
  filter: string[],
  parent: string,
  unassigned = false,
  compareStore: string | null = null,
) {
  return useQuery({
    queryKey: salesComparisonKey(storeId, kind, periods, filter, parent, unassigned, compareStore),
    staleTime: REPORT_STALE_TIME,
    queryFn: () => {
      const params = new URLSearchParams({
        previousFrom: periods.previous.from,
        previousTo: periods.previous.to,
        currentFrom: periods.current.from,
        currentTo: periods.current.to,
        parent,
      })
      if (unassigned) params.set('unassigned', 'true')
      if (compareStore) params.set('compareStore', compareStore)
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
  enabled = true,
) {
  return useQuery({
    enabled,
    queryKey: [
      ...reportsKey(storeId),
      `${kind}-history`,
      target.kind === 'article' ? target.itm8 : target,
      range,
      filter,
    ],
    staleTime: REPORT_STALE_TIME,
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

/** Ostatnie dostawy artykułu (przyjęcia PZ), od najnowszej. */
export function useArticleDeliveries(storeId: string, itm8: string | null) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'article', itm8, 'deliveries'],
    queryFn: () => api<ArticleDelivery[]>(`${reportsPath(storeId)}/articles/${itm8}/deliveries`),
    enabled: itm8 != null,
  })
}

/** Ostatnie protokoły artykułu inne niż przyjęcia i korekty, od najnowszego. */
export function useArticleProtocols(storeId: string, itm8: string) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'article', itm8, 'protocols'],
    queryFn: () => api<ArticleProtocol[]>(`${reportsPath(storeId)}/articles/${itm8}/protocols`),
  })
}

/** Kody artykułu: EAN-y z kartoteki, multikodów i par z raportów oraz inne artykuły z tymi kodami. */
export function useArticleCodes(storeId: string, itm8: string, enabled: boolean) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'article', itm8, 'codes'],
    queryFn: () => api<ArticleCodes>(`${reportsPath(storeId)}/articles/${itm8}/codes`),
    enabled,
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
    staleTime: REPORT_STALE_TIME,
    queryFn: () =>
      api<PeriodSummary>(
        `${reportsPath(storeId)}/period-summary?${new URLSearchParams({ from: range.from, to: range.to })}`,
      ),
    enabled,
    placeholderData: keepPreviousData,
  })
}

/** Protokoły do raportu protokołów (bez wyłączonych w Ustawieniach sklepu) i do kiedy są wgrane dokumenty. */
export function useProtocolReportsOverview(storeId: string) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'protocols'],
    queryFn: () => api<ProtocolReportsOverview>(`${reportsPath(storeId)}/protocols`),
  })
}

export type ProtocolReportQuery = {
  protocol: ProtocolKind
  grouping: ProtocolReportGrouping
  level: 'REGION' | 'FAMILY' | 'SUBFAMILY'
  range: DateRange
  /** {@code null} = bez porównania. */
  previous: DateRange | null
}

/** Protokół za okres (opcjonalnie z okresem porównywanym), po artykule, marce, ostatnim dostawcy albo nomenklaturze. */
export function useProtocolReport(storeId: string, query: ProtocolReportQuery, enabled: boolean) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'protocols', query],
    staleTime: REPORT_STALE_TIME,
    queryFn: () => {
      const params = new URLSearchParams({ grouping: query.grouping, from: query.range.from, to: query.range.to })
      if (query.grouping === 'NOMENCLATURE') params.set('level', query.level)
      if (query.previous) {
        params.set('previousFrom', query.previous.from)
        params.set('previousTo', query.previous.to)
      }
      return api<ProtocolReport>(`${reportsPath(storeId)}/protocols/${query.protocol}?${params}`)
    },
    enabled,
    placeholderData: keepPreviousData,
  })
}

export type ProtocolReportPrint = ProtocolReportQuery & {
  /** Klucze grup do wydruku; pusta lista = wszystkie. */
  groups: string[]
  articles: boolean
}

const protocolPdfRequest = (storeId: string, print: ProtocolReportPrint) => ({
  path: `${reportsPath(storeId)}/protocols/${print.protocol}/pdf`,
  body: {
    grouping: print.grouping,
    level: print.grouping === 'NOMENCLATURE' ? print.level : null,
    from: print.range.from,
    to: print.range.to,
    previousFrom: print.previous?.from ?? null,
    previousTo: print.previous?.to ?? null,
    groups: print.groups,
    articles: print.articles,
  },
})

/** Wydruk raportu protokołu z wybranymi grupami (np. marką do pokazania dostawcy). */
export function useProtocolReportPdf(storeId: string) {
  return {
    print: useMutation({
      mutationFn: (print: ProtocolReportPrint) => {
        const { path, body } = protocolPdfRequest(storeId, print)
        return printPostedFile(path, body)
      },
      meta: { invalidates: false },
    }),
    download: useMutation({
      mutationFn: (print: ProtocolReportPrint) => {
        const { path, body } = protocolPdfRequest(storeId, print)
        return downloadPostedFile(path, body, 'protokol.pdf')
      },
      meta: { invalidates: false },
    }),
  }
}

/** Rozwinięty wiersz podsumowania: `parent` = kod rejonu, rodziny albo podrodziny; `unassigned` = bez nomenklatury. */
export function usePeriodSummaryChildren(storeId: string, range: DateRange, parent: string, unassigned: boolean) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'period-summary', range, 'children', parent, unassigned],
    staleTime: REPORT_STALE_TIME,
    queryFn: () =>
      api<PeriodSummaryLevel>(
        `${reportsPath(storeId)}/period-summary/children?${new URLSearchParams({
          from: range.from,
          to: range.to,
          parent,
          unassigned: String(unassigned),
        })}`,
      ),
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

/** Miesiące raportu gratisów: od i do (RRRR-MM). */
export type MonthRange = { from: string; to: string }

/** Raport przyjętych gratisów za miesiące (kafelek na miesiąc, przy kilku też podsumowanie). */
export function useGratisReport(storeId: string, range: MonthRange) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'gratis', range],
    staleTime: REPORT_STALE_TIME,
    queryFn: () => api<GratisReport>(`${reportsPath(storeId)}/gratis?${new URLSearchParams(range)}`),
    placeholderData: keepPreviousData,
  })
}

const gratisPdfPath = (storeId: string, range: MonthRange) =>
  `${reportsPath(storeId)}/gratis/pdf?${new URLSearchParams(range)}`

/** Wydruk raportu gratisów: PDF od razu w oknie drukowania albo zapis pliku. */
export function useGratisReportPdf(storeId: string) {
  return {
    print: useMutation({
      mutationFn: (range: MonthRange) => printFile(gratisPdfPath(storeId, range)),
      meta: { invalidates: false },
    }),
    download: useMutation({
      mutationFn: (range: MonthRange) => downloadFile(gratisPdfPath(storeId, range), 'gratisy.pdf'),
      meta: { invalidates: false },
    }),
  }
}

const zeroStockQuery = (codes: string[]) => new URLSearchParams(codes.map((code) => ['nomenclatures', code])).toString()

/** Raport zerówek dla potwierdzonego wyboru nomenklatury. */
export function useZeroStock(storeId: string, codes: string[]) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'zero-stock', codes],
    enabled: codes.length > 0,
    staleTime: REPORT_STALE_TIME,
    queryFn: () => api<ZeroStockReport>(`${reportsPath(storeId)}/zero-stock?${zeroStockQuery(codes)}`),
    placeholderData: keepPreviousData,
  })
}

const zeroStockPdfPath = (storeId: string, codes: string[]) =>
  `${reportsPath(storeId)}/zero-stock/pdf?${zeroStockQuery(codes)}`

/** Wydruk raportu zerówek z kodami kreskowymi: PDF od razu w oknie drukowania albo zapis pliku. */
export function useZeroStockPdf(storeId: string) {
  return {
    print: useMutation({
      mutationFn: (codes: string[]) => printFile(zeroStockPdfPath(storeId, codes)),
      meta: { invalidates: false },
    }),
    download: useMutation({
      mutationFn: (codes: string[]) => downloadFile(zeroStockPdfPath(storeId, codes), 'zerowki.pdf'),
      meta: { invalidates: false },
    }),
  }
}

export type StockGapQuery = {
  minWeekly: number
  maxStock: number
  noDeliveryDays: number
  nomenclatures: string[]
}

/** Braki towaru: zakładki, wyszukiwarkę i sortowanie robi strona, bo pozycji jest najwyżej kilkaset. */
export function useStockGaps(storeId: string, query: StockGapQuery, enabled: boolean) {
  const { nomenclatures, ...thresholds } = query
  const search = new URLSearchParams(Object.entries(thresholds).map(([key, value]) => [key, String(value)]))
  nomenclatures.forEach((code) => search.append('nomenclatures', code))
  return useQuery({
    queryKey: [...reportsKey(storeId), 'stock-gaps', query],
    enabled,
    staleTime: REPORT_STALE_TIME,
    queryFn: () => api<StockGapReport>(`${reportsPath(storeId)}/stock-gaps?${search}`),
    placeholderData: keepPreviousData,
  })
}

export type NegativeStockQuery = {
  delivery: NegativeStockDelivery
  days: number
  from: string | null
  to: string | null
  withProduction: boolean
  nomenclatures: string[]
  /** O ile % stan podobnego artykułu na plusie może się różnić od brakującej ilości. */
  pairTolerance: number
}

/** Ujemne stany: sortowanie i wyszukiwanie robi strona, bo artykułów jest 2-3 tys. */
export function useNegativeStock(storeId: string, query: NegativeStockQuery, enabled: boolean) {
  const search = new URLSearchParams({
    delivery: query.delivery,
    days: String(query.days),
    withProduction: String(query.withProduction),
    pairTolerance: String(query.pairTolerance),
  })
  if (query.delivery === 'DELIVERED_IN' && query.from && query.to) {
    search.set('from', query.from)
    search.set('to', query.to)
  }
  query.nomenclatures.forEach((code) => search.append('nomenclatures', code))
  return useQuery({
    queryKey: [...reportsKey(storeId), 'negative-stock', query],
    enabled,
    staleTime: REPORT_STALE_TIME,
    queryFn: () => api<NegativeStockReport>(`${reportsPath(storeId)}/negative-stock?${search}`),
    placeholderData: keepPreviousData,
  })
}

export type SlowMovingSort = 'value' | 'name' | 'itm8' | 'supplier' | 'delivery' | 'stock' | 'sold' | 'rotation'

export type SlowMovingQuery = {
  range: DateRange
  maxRotation: number
  nomenclatures: string[]
  category: string | null
  supplier: string | null
  cadencier: 'WITH' | 'WITHOUT' | null
  origin: 'BASE' | 'OUTSIDE' | null
  minStockValue: number | null
  query: string
  withoutDeliveries: boolean
  withRawMaterials: boolean
  deliveryDays: number | null
  sort: SlowMovingSort
  direction: 'asc' | 'desc'
}

function slowMovingParams({ range, nomenclatures, ...filters }: SlowMovingQuery) {
  const params = new URLSearchParams(range)
  nomenclatures.forEach((code) => params.append('nomenclatures', code))
  Object.entries(filters).forEach(([key, value]) => {
    if (value != null && value !== '' && value !== false) params.set(key, String(value))
  })
  return params
}

/** Filtry liczy serwer, żeby wydruk miał dokładnie to, co ekran. */
export function useSlowMoving(storeId: string, query: SlowMovingQuery, enabled: boolean) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'slow-moving', query],
    enabled,
    staleTime: REPORT_STALE_TIME,
    queryFn: () => api<SlowMovingReport>(`${reportsPath(storeId)}/slow-moving?${slowMovingParams(query)}`),
    placeholderData: keepPreviousData,
  })
}

const slowMovingPdfPath = (storeId: string, query: SlowMovingQuery) =>
  `${reportsPath(storeId)}/slow-moving/pdf?${slowMovingParams(query)}`

/** Wydruk niesprzedających się artykułów: PDF od razu w oknie drukowania albo zapis pliku. */
export function useSlowMovingPdf(storeId: string) {
  return {
    print: useMutation({
      mutationFn: (query: SlowMovingQuery) => printFile(slowMovingPdfPath(storeId, query)),
      meta: { invalidates: false },
    }),
    download: useMutation({
      mutationFn: (query: SlowMovingQuery) => downloadFile(slowMovingPdfPath(storeId, query), 'niesprzedajace-sie.pdf'),
      meta: { invalidates: false },
    }),
  }
}

const slowMovingExcludedPath = (storeId: string) => `${reportsPath(storeId)}/slow-moving/excluded`

/** Artykuły pominięte w raporcie niesprzedających się, wspólne dla całego sklepu. */
export function useSlowMovingExcluded(storeId: string) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'slow-moving-excluded'],
    queryFn: () => api<SlowMovingExcludedItem[]>(slowMovingExcludedPath(storeId)),
  })
}

export function useExcludeSlowMoving(storeId: string) {
  return useMutation({
    mutationFn: (itm8: string) =>
      api<SlowMovingExcludedItem>(slowMovingExcludedPath(storeId), { method: 'POST', json: { itm8 } }),
  })
}

export function useRemoveSlowMovingExcluded(storeId: string) {
  return useMutation({
    mutationFn: (id: string) => api<void>(`${slowMovingExcludedPath(storeId)}/${id}`, { method: 'DELETE' }),
  })
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

const priceCheckPath = (storeId: string) => `${reportsPath(storeId)}/price-check`
const priceCheckSettingsKey = (storeId: string) => [...reportsKey(storeId), 'price-check', 'settings'] as const

/** Zakup bazowy poza bazą: przyjęcia z ostatnich 30 dni z artykułami z cadencier od innych dostawców. */
export function usePriceCheck(storeId: string) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'price-check', 'report'],
    queryFn: () => api<PriceCheckReport>(priceCheckPath(storeId)),
  })
}

export type TopSalesQuery = {
  range: DateRange
  metric: TopSalesMetric
  level: TopSalesLevel
  limit: number
  minQuantity: number
  filter: string[]
  compared: string[]
  rankStore: string
}

/** Topy sprzedażowe; top ilościowy sprawdza promocje, więc za rok liczy się kilka sekund. */
export function useTopSales(storeId: string, query: TopSalesQuery, enabled: boolean) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'top-sales', query],
    staleTime: REPORT_STALE_TIME,
    placeholderData: keepPreviousData,
    queryFn: () => {
      const params = new URLSearchParams({
        from: query.range.from,
        to: query.range.to,
        metric: query.metric,
        level: query.level,
        limit: String(query.limit),
        minQuantity: String(query.minQuantity),
        rankStore: query.rankStore,
      })
      query.filter.forEach((code) => params.append('nomenclatures', code))
      query.compared.forEach((store) => params.append('compareStores', store))
      return api<TopSalesReport>(`${reportsPath(storeId)}/top-sales?${params}`)
    },
    enabled,
  })
}

/** Sklepy zaprzyjaźnione do porównania w topach sprzedażowych. */
export function useTopSalesPartners(storeId: string) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'top-sales', 'stores'],
    queryFn: () => api<TopSalesPartner[]>(`${reportsPath(storeId)}/top-sales/stores`),
  })
}

/** Raport lady: wybrane rejony 24, 36, 46 w bieżącym sklepie i porównywanych. */
export type CounterQuery = {
  range: DateRange
  regions: string[]
  stores: string[]
}

const counterParams = (query: CounterQuery) => {
  const params = new URLSearchParams({ from: query.range.from, to: query.range.to })
  query.regions.forEach((code) => params.append('regions', code))
  query.stores.forEach((store) => params.append('stores', store))
  return params
}

export function useCounterReport(storeId: string, query: CounterQuery, enabled: boolean) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'counter', query],
    staleTime: REPORT_STALE_TIME,
    placeholderData: keepPreviousData,
    queryFn: () => api<CounterReport>(`${reportsPath(storeId)}/counter?${counterParams(query)}`),
    enabled,
  })
}

/** Sklepy do porównania w raporcie lady: dostępne użytkownikowi i zaprzyjaźnione. */
export function useCounterStores(storeId: string) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'counter', 'stores'],
    queryFn: () => api<CounterStoreOption[]>(`${reportsPath(storeId)}/counter/stores`),
  })
}

export type CounterPrint = CounterQuery & { metrics: CounterMetric[]; basis: 'GROSS' | 'NET'; articles: boolean }

const counterPdfPath = (storeId: string, print: CounterPrint) => {
  const params = counterParams(print)
  print.metrics.forEach((metric) => params.append('metrics', metric))
  params.set('basis', print.basis)
  params.set('articles', String(print.articles))
  return `${reportsPath(storeId)}/counter/pdf?${params}`
}

export function useCounterPdf(storeId: string) {
  return {
    print: useMutation({
      mutationFn: (print: CounterPrint) => printFile(counterPdfPath(storeId, print)),
      meta: { invalidates: false },
    }),
    download: useMutation({
      mutationFn: (print: CounterPrint) => downloadFile(counterPdfPath(storeId, print), 'raport-lady.pdf'),
      meta: { invalidates: false },
    }),
  }
}

/** Przepłacone artykuły: przyjęcia z ostatnich 30 dni droższe niż poprzednie dostawy. */
export function useOverpayments(storeId: string, maxReferenceDays: number) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'overpayments', maxReferenceDays],
    queryFn: () => api<OverpaymentReport>(`${reportsPath(storeId)}/overpayments?maxReferenceDays=${maxReferenceDays}`),
    placeholderData: keepPreviousData,
  })
}

/** Marże poza normą z przyjęć z ostatnich `days` dni (najwyżej 365). */
export function useLowMargins(storeId: string, days: number) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'low-margins', days],
    queryFn: () => api<LowMarginReport>(`${reportsPath(storeId)}/low-margins?days=${days}`),
    placeholderData: keepPreviousData,
  })
}

/** Progi marży raportu marż poza normą dla sklepu; raport pobiera się od nowa. */
export function useSetLowMarginGaps(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (gaps: { lowGap: number; highGap: number }) =>
      api<LowMarginReport>(`${reportsPath(storeId)}/low-margins/settings`, { method: 'PUT', json: gaps }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [...reportsKey(storeId), 'low-margins'] }),
    meta: { invalidates: false },
  })
}

export type ExcludedSuppliersReport = 'low-margins' | 'overpayments'

/** Dostawcy pomijani w raporcie. Raport ma w kluczu swoje filtry, więc pobieramy go od nowa, a reszty nie ruszamy. */
export function useSetExcludedSuppliers(storeId: string, path: ExcludedSuppliersReport) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (supplierCodes: string[]) =>
      api<LowMarginReport | OverpaymentReport>(`${reportsPath(storeId)}/${path}/excluded-suppliers`, {
        method: 'PUT',
        json: { supplierCodes },
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [...reportsKey(storeId), path] }),
    scope: { id: `${path}-excluded-suppliers-${storeId}` },
    meta: { invalidates: false },
  })
}

/** Skuteczność promocji kończących się w okresie (najwyżej 92 dni; liczy się kilka sekund). */
export function usePromotionReport(storeId: string, range: DateRange, enabled: boolean) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'promotions', range],
    staleTime: REPORT_STALE_TIME,
    queryFn: () =>
      api<PromotionReport>(
        `${reportsPath(storeId)}/promotions?${new URLSearchParams({ from: range.from, to: range.to })}`,
      ),
    enabled,
    placeholderData: keepPreviousData,
  })
}

/** Lista artykułów obecnych w cadencier (Ustawienia sklepu): ile ich jest i ostatnie wgrania. */
export function useCadencier(storeId: string, enabled = true) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'price-check', 'cadencier'],
    queryFn: () => api<CadencierOverview>(`${priceCheckPath(storeId)}/cadencier`),
    enabled,
  })
}

export function useImportCadencier(storeId: string) {
  return useMutation({
    mutationFn: (file: File) => {
      const formData = new FormData()
      formData.append('file', file)
      return api<CadencierImport>(`${priceCheckPath(storeId)}/cadencier`, { method: 'POST', formData })
    },
  })
}

/** Incydenty zamówień do bazy: najnowszy dzień i ostatnie wgrania. */
export function useIncidents(storeId: string, enabled = true) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'price-check', 'incidents'],
    queryFn: () => api<IncidentOverview>(`${priceCheckPath(storeId)}/incidents`),
    enabled,
  })
}

export function useImportIncidents(storeId: string) {
  return useMutation({
    mutationFn: (file: File) => {
      const formData = new FormData()
      formData.append('file', file)
      return api<IncidentImport>(`${priceCheckPath(storeId)}/incidents`, { method: 'POST', formData })
    },
  })
}

/** Minimalna marża artykułu z cadencier kupionego u innego dostawcy (Ustawienia sklepu). */
export function usePriceCheckSettings(storeId: string, enabled = true) {
  return useQuery({
    queryKey: priceCheckSettingsKey(storeId),
    queryFn: () => api<PriceCheckSettings>(`${priceCheckPath(storeId)}/settings`),
    enabled,
  })
}

export function useSavePriceCheckSettings(storeId: string) {
  return useMutation({
    mutationFn: (minMarginPercent: number | null) =>
      api<PriceCheckSettings>(`${priceCheckPath(storeId)}/settings`, { method: 'PUT', json: { minMarginPercent } }),
  })
}

/** Własne kategorie raportu (druga linia zakładek), w kolejności dodania. */
export function usePriceCheckCategories(storeId: string) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'price-check', 'categories'],
    queryFn: () => api<PriceCheckCategory[]>(`${priceCheckPath(storeId)}/categories`),
  })
}

/** Dodanie, zmiana, usunięcie (znacznik) i przywrócenie kategorii raportu. */
export function usePriceCheckCategoryActions(storeId: string) {
  const path = `${priceCheckPath(storeId)}/categories`
  return {
    save: useMutation({
      mutationFn: ({ id, name, regionCodes }: { id?: string; name: string; regionCodes: string[] }) =>
        api<PriceCheckCategory>(id ? `${path}/${id}` : path, {
          method: id ? 'PUT' : 'POST',
          json: { name, regionCodes },
        }),
    }),
    remove: useMutation({
      mutationFn: (id: string) => api<void>(`${path}/${id}`, { method: 'DELETE' }),
    }),
    restore: useMutation({
      mutationFn: (id: string) => api<PriceCheckCategory>(`${path}/${id}/restore`, { method: 'POST' }),
    }),
  }
}

/** Raport, którego dotyczy status wyjaśniania: statusy obu raportów są niezależne. */
export type ReviewedReport = 'BASE_PURCHASE' | 'OVERPAYMENT' | 'LOW_MARGIN'

/** Status wyjaśniania przyjęcia: nowy wpis, cofnięcie (znacznik) i przywrócenie; każde zwraca historię. */
export function usePriceReviewActions(storeId: string, report: ReviewedReport) {
  const reportPath = {
    BASE_PURCHASE: priceCheckPath(storeId),
    OVERPAYMENT: `${reportsPath(storeId)}/overpayments`,
    LOW_MARGIN: `${reportsPath(storeId)}/low-margins`,
  }[report]
  return {
    review: useMutation({
      mutationFn: ({ documentId, status, note }: { documentId: string; status: PriceCheckStatus; note: string }) =>
        api<PriceCheckReview[]>(`${reportPath}/documents/${documentId}/reviews`, {
          method: 'POST',
          json: { status, note },
        }),
    }),
    remove: useMutation({
      mutationFn: (reviewId: string) =>
        api<PriceCheckReview[]>(`${priceCheckPath(storeId)}/reviews/${reviewId}`, { method: 'DELETE' }),
    }),
    restore: useMutation({
      mutationFn: (reviewId: string) =>
        api<PriceCheckReview[]>(`${priceCheckPath(storeId)}/reviews/${reviewId}/restore`, { method: 'POST' }),
    }),
  }
}

/** Dostawcy z przyjęć PZ sklepu do wyboru w obrotach z dostawcami. */
export function useTurnoverSuppliers(storeId: string) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'supplier-turnover', 'suppliers'],
    staleTime: REPORT_STALE_TIME,
    queryFn: () => api<TurnoverSupplier[]>(`${reportsPath(storeId)}/supplier-turnover/suppliers`),
  })
}

/** Zakup netto od dostawcy po miesiącach i nomenklaturze. */
export function useSupplierTurnover(storeId: string, supplier: string | null, range: DateRange, enabled: boolean) {
  return useQuery({
    queryKey: [...reportsKey(storeId), 'supplier-turnover', supplier, range],
    staleTime: REPORT_STALE_TIME,
    queryFn: () =>
      api<SupplierTurnoverReport>(
        `${reportsPath(storeId)}/supplier-turnover?${new URLSearchParams({ supplier: supplier!, from: range.from, to: range.to })}`,
      ),
    enabled: enabled && supplier != null,
    placeholderData: keepPreviousData,
  })
}
