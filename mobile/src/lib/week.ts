import { addDays, money, quantity } from './format'
import type { DashboardChartMetric, LeaveKind, SalesFigures } from './types'

/** Dzień tygodnia 0 = poniedziałek … 6 = niedziela, dla daty RRRR-MM-DD. */
export function weekdayIndex(iso: string) {
  const [y, m, d] = iso.split('-').map(Number)
  return (new Date(Date.UTC(y, m - 1, d)).getUTCDay() + 6) % 7
}

export const WEEKDAYS_SHORT = ['Pn', 'Wt', 'Śr', 'Cz', 'Pt', 'So', 'Nd']
export const WEEKDAYS = ['Poniedziałek', 'Wtorek', 'Środa', 'Czwartek', 'Piątek', 'Sobota', 'Niedziela']

/** Dzisiejsza data w strefie telefonu (sklepy są w Polsce, tak jak telefony pracowników). */
export function todayIso() {
  const now = new Date()
  const local = new Date(now.getTime() - now.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 10)
}

export const mondayOf = (iso: string) => addDays(iso, -weekdayIndex(iso))

/** 52 tygodnie wstecz: te same dni tygodnia rok wcześniej, jak w porównaniu sprzedaży (YEAR_AGO_DAYS). */
export const YEAR_AGO_DAYS = 364

/** Ile tygodni wstecz pokazuje pasek trendu pod wykresem. */
export const TREND_WEEKS = 12

/**
 * Okno wyników: {@link TREND_WEEKS} tygodni po 7 dni kończących się na ostatnim dniu ze sprzedażą (ostatni z nich to
 * tydzień na wykresie) i te same dni tygodnia rok wcześniej.
 */
export function storeWindow(lastSaleDate: string) {
  const current = { from: addDays(lastSaleDate, -(TREND_WEEKS * 7 - 1)), to: lastSaleDate }
  const yearAgo = { from: addDays(current.from, -YEAR_AGO_DAYS), to: addDays(current.to, -YEAR_AGO_DAYS) }
  return { current, yearAgo }
}

/** Dzień bez żadnego wiersza sprzedaży: zwykle niewgrany raport, a nie zerowa sprzedaż. */
export const hasNoSales = (figures: SalesFigures) => figures.quantity === 0 && figures.saleValueGross === 0

/** Zmiana % wobec roku temu; `null`, gdy rok temu nie było z czym porównać. */
export const changePercent = (now: number, before: number) =>
  before !== 0 ? ((now - before) / Math.abs(before)) * 100 : null

/** Zmiana ze znakiem: +12,3% albo −4%. */
export const formatChange = (change: number) =>
  `${change >= 0 ? '+' : '−'}${Math.abs(change).toFixed(1).replace(/\.0$/, '').replace('.', ',')}%`

export const METRICS: { value: DashboardChartMetric; label: string }[] = [
  { value: 'SALES', label: 'Sprzedaż' },
  { value: 'MARGIN', label: 'Marża' },
  { value: 'QUANTITY', label: 'Ilość' },
]

export function metricValue(metric: DashboardChartMetric, figures: SalesFigures) {
  if (metric === 'QUANTITY') return figures.quantity
  if (metric === 'MARGIN') return figures.marginValue ?? 0
  return figures.saleValueGross
}

export function formatMetric(metric: DashboardChartMetric, value: number) {
  return metric === 'QUANTITY' ? `${quantity(Math.round(value))} szt.` : money(Math.round(value))
}

/** Krótki zapis do osi i słupków: 12,3 tys. */
export function compactMetric(metric: DashboardChartMetric, value: number) {
  const unit = metric === 'QUANTITY' ? '' : ' zł'
  if (Math.abs(value) >= 1_000_000) return `${(value / 1_000_000).toFixed(1).replace('.', ',')} mln${unit}`
  if (Math.abs(value) >= 1000) return `${(value / 1000).toFixed(1).replace('.', ',')} tys.${unit}`
  return `${Math.round(value)}${unit}`
}

export const LEAVE_LABELS: Record<LeaveKind, string> = {
  ANNUAL: 'Urlop wypoczynkowy',
  ON_DEMAND: 'Urlop na żądanie',
  CHILD_CARE: 'Opieka nad dzieckiem',
  FORCE_MAJEURE: 'Siła wyższa',
  CARE: 'Urlop opiekuńczy',
  REHABILITATION: 'Turnus rehabilitacyjny',
  OCCASIONAL: 'Urlop okolicznościowy',
  UNPAID: 'Urlop bezpłatny',
}

/** Godzina z API (GG:MM albo GG:MM:SS) jako G:MM. */
export const hour = (value: string) => value.slice(0, 5).replace(/^0/, '')

/** Długość zmiany w minutach; koniec przed początkiem = zmiana przez północ. */
export function shiftMinutes(start: string, end: string) {
  const minutes = (value: string) => Number(value.slice(0, 2)) * 60 + Number(value.slice(3, 5))
  const length = minutes(end) - minutes(start)
  return length <= 0 ? length + 24 * 60 : length
}

export const hoursLabel = (minutes: number) => `${Math.floor(minutes / 60)}:${String(minutes % 60).padStart(2, '0')}`
