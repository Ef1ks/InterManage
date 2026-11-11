import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/lib/api'
import { addDays } from '@/lib/dates'
import type { DashboardChartMetric, DashboardSettings, SalesFigures, SalesHistory } from '@/lib/types'
import { YEAR_AGO_DAYS } from '@/features/sales/periods'

const SETTINGS_KEY = ['dashboard-settings'] as const

/** Ustawienia pulpitu konta, wspólne z aplikacją mobilną (panel konta). */
export function useDashboardSettings(enabled = true) {
  return useQuery({
    queryKey: SETTINGS_KEY,
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
    // Sam wygląd pulpitu: nie unieważniamy wszystkich zapytań, wykres przełącza się od razu.
    meta: { invalidates: false },
    onMutate: (settings) => {
      const previous = queryClient.getQueryData<DashboardSettings>(SETTINGS_KEY)
      queryClient.setQueryData(SETTINGS_KEY, settings)
      return { previous }
    },
    onError: (_error, _settings, context) => queryClient.setQueryData(SETTINGS_KEY, context?.previous),
    onSuccess: (saved) => queryClient.setQueryData(SETTINGS_KEY, saved),
  })
}

export type DateRange = { from: string; to: string }

/** Ile tygodni wstecz pokazuje pasek trendu pod wykresem. */
export const TREND_WEEKS = 12

/**
 * Okno wyników: {@link TREND_WEEKS} tygodni po 7 dni kończących się na ostatnim dniu ze sprzedażą (ostatni z nich to
 * tydzień na wykresie) i te same dni tygodnia rok wcześniej (52 tygodnie wstecz).
 */
export function storeWindow(lastSaleDate: string): { current: DateRange; yearAgo: DateRange } {
  const current = { from: addDays(lastSaleDate, -(TREND_WEEKS * 7 - 1)), to: lastSaleDate }
  return {
    current,
    yearAgo: { from: addDays(current.from, -YEAR_AGO_DAYS), to: addDays(current.to, -YEAR_AGO_DAYS) },
  }
}

/** Sprzedaż całego sklepu dzień po dniu (bez artykułu i nomenklatury), to samo co wykres całego sklepu w raportach. */
export function useStoreHistory(storeId: string, range: DateRange | null) {
  return useQuery({
    queryKey: ['stores', storeId, 'reports', 'store-history', range],
    queryFn: () => api<SalesHistory>(`/stores/${storeId}/reports/sales-history?${new URLSearchParams(range!)}`),
    enabled: range != null,
    staleTime: 10 * 60_000,
    placeholderData: keepPreviousData,
  })
}

export function metricValue(metric: DashboardChartMetric, figures: SalesFigures) {
  if (metric === 'QUANTITY') return figures.quantity
  if (metric === 'MARGIN') return figures.marginValue ?? 0
  return figures.saleValueGross
}

/** Dzień bez żadnego wiersza sprzedaży: zwykle niewgrany raport, a nie zerowa sprzedaż. */
export const hasNoSales = (figures: SalesFigures) => figures.quantity === 0 && figures.saleValueGross === 0

/** Zmiana % wobec roku temu; `null`, gdy rok temu nie było z czym porównać. */
export const changePercent = (now: number, before: number) =>
  before !== 0 ? ((now - before) / Math.abs(before)) * 100 : null

/**
 * {@code skipped}: dni tego tygodnia pominięte w zmianie %, bo one albo ich odpowiednik rok temu nie mają sprzedaży
 * (święto, zamknięty sklep, niewgrany raport).
 */
export type WeekChange = {
  from: string
  to: string
  now: number
  before: number
  change: number | null
  skipped: string[]
}

/**
 * Tygodnie okna od najstarszego: suma miary i zmiana wobec tych samych dni rok temu. Zmiana liczy się tylko z par dni,
 * w których obie strony mają sprzedaż, więc jedno zamknięte święto (np. 1.11) nie zaniża tygodnia o 1/7 ani nie
 * zabiera mu porównania. Sumy {@code now} i {@code before} są pełne, jak sprzedaż tygodnia.
 */
export function weeklyChanges(
  metric: DashboardChartMetric,
  current: SalesHistory,
  yearAgo: SalesHistory,
): WeekChange[] {
  const weeks: WeekChange[] = []
  for (let start = 0; start + 7 <= current.days.length; start += 7) {
    const days = current.days.slice(start, start + 7)
    const before = yearAgo.days.slice(start, start + 7)
    const now = days.reduce((sum, day) => sum + metricValue(metric, day.figures), 0)
    const then = before.reduce((sum, day) => sum + metricValue(metric, day.figures), 0)
    let pairedNow = 0
    let pairedBefore = 0
    const skipped: string[] = []
    days.forEach((day, index) => {
      const previous = before[index]
      if (!previous || hasNoSales(day.figures) || hasNoSales(previous.figures)) {
        skipped.push(day.date)
        return
      }
      pairedNow += metricValue(metric, day.figures)
      pairedBefore += metricValue(metric, previous.figures)
    })
    weeks.push({
      from: days[0].date,
      to: days[6].date,
      now,
      before: then,
      change: skipped.length < days.length ? changePercent(pairedNow, pairedBefore) : null,
      skipped,
    })
  }
  return weeks
}
