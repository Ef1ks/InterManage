import { addDays, parseIsoDate, toIsoDate } from '@/lib/dates'

export type PeriodMode = 'day' | 'week' | 'month' | 'custom'

/** Zakres dni RRRR-MM-DD, oba końce włącznie. */
export type DateRange = { from: string; to: string }
export type Periods = { previous: DateRange; current: DateRange }

export const PERIOD_MODES: { value: PeriodMode; label: string }[] = [
  { value: 'day', label: 'Dzień' },
  { value: 'week', label: 'Tydzień' },
  { value: 'month', label: 'Miesiąc' },
  { value: 'custom', label: 'Własny okres' },
]

/** Najdłuższy okres, jak SalesReportService.MAX_PERIOD_DAYS. */
export const MAX_PERIOD_DAYS = 366

/**
 * Okres aktualny i ten sam okres rok wcześniej, porównywane w całości (2 dni do 2 dni, nie dzień po dniu).
 * Tydzień i miesiąc w toku kończą się na ostatnim dniu ze sprzedażą ({@code lastSaleDate}).
 */
export function comparePeriods(mode: PeriodMode, anchor: string, custom: DateRange, lastSaleDate: string | null): Periods {
  const current = currentPeriod(mode, anchor, custom, lastSaleDate)
  return { current, previous: { from: addDays(current.from, -YEAR_AGO_DAYS), to: addDays(current.to, -YEAR_AGO_DAYS) } }
}

/**
 * Rok wcześniej to 52 pełne tygodnie wstecz, a nie ta sama data: poniedziałek-niedziela 19-25.10.2026
 * porównuje się z poniedziałkiem-niedzielą 20-26.10.2025, bo sprzedaż zależy od dnia tygodnia.
 */
export const YEAR_AGO_DAYS = 364

function currentPeriod(mode: PeriodMode, anchor: string, custom: DateRange, lastSaleDate: string | null): DateRange {
  if (mode === 'day') return { from: anchor, to: anchor }
  if (mode === 'week') {
    const from = weekStart(anchor)
    return { from, to: clampToData(from, addDays(from, 6), lastSaleDate) }
  }
  if (mode === 'month') {
    const from = monthStart(anchor)
    return { from, to: clampToData(from, monthEnd(from), lastSaleDate) }
  }
  return custom
}

/** Data, od której liczy się sąsiedni okres (strzałki obok wyboru daty). */
export function shiftAnchor(mode: Exclude<PeriodMode, 'custom'>, anchor: string, step: -1 | 1): string {
  if (mode === 'day') return addDays(anchor, step)
  if (mode === 'week') return addDays(weekStart(anchor), 7 * step)
  return shiftMonth(monthStart(anchor), step)
}

export function dayCount(range: DateRange): number {
  return Math.round((parseIsoDate(range.to).getTime() - parseIsoDate(range.from).getTime()) / 86_400_000) + 1
}

/** Okres w toku kończy się na ostatnim dniu ze sprzedażą, o ile ten dzień w nim leży. */
function clampToData(from: string, to: string, lastSaleDate: string | null) {
  return lastSaleDate && lastSaleDate >= from && lastSaleDate < to ? lastSaleDate : to
}

function weekStart(value: string) {
  return addDays(value, -((parseIsoDate(value).getDay() + 6) % 7))
}

function monthStart(value: string) {
  return `${value.slice(0, 7)}-01`
}

function monthEnd(value: string) {
  const date = parseIsoDate(value)
  return toIsoDate(new Date(date.getFullYear(), date.getMonth() + 1, 0))
}

/** Pierwszy dzień miesiąca przesunięty o {@code months}. */
function shiftMonth(first: string, months: number) {
  const date = parseIsoDate(first)
  return toIsoDate(new Date(date.getFullYear(), date.getMonth() + months, 1))
}

const withYear = new Intl.DateTimeFormat('pl-PL', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })
const withoutYear = new Intl.DateTimeFormat('pl-PL', { weekday: 'short', day: 'numeric', month: 'short' })

/**
 * Z dniami tygodnia, bo rok temu porównujemy te same dni tygodnia: "pon., 26 paź 2026",
 * "pon., 19 paź – niedz., 25 paź 2026", "wt., 30 gru 2025 – pt., 2 sty 2026".
 */
export function formatRange({ from, to }: DateRange): string {
  const start = parseIsoDate(from)
  const end = parseIsoDate(to)
  if (from === to) return withYear.format(end)
  const startLabel = from.slice(0, 4) === to.slice(0, 4) ? withoutYear.format(start) : withYear.format(start)
  return `${startLabel} – ${withYear.format(end)}`
}

/** "1 dzień", "2 dni". */
export function daysLabel(days: number) {
  return days === 1 ? '1 dzień' : `${days} dni`
}
