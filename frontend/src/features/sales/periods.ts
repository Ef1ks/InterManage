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
 * Z czym porównujemy okres aktualny: ten sam okres rok wcześniej, okres tuż przed nim, ten sam dzień tydzień
 * wcześniej albo dowolny okres wybrany ręcznie. Porównanie zawsze w całości (2 dni do 2 dni, nie dzień po dniu).
 */
export type CompareMode = 'year' | 'previous' | 'week' | 'custom'

const PREVIOUS_LABELS: Record<PeriodMode, string> = {
  day: 'Poprzedni dzień',
  week: 'Poprzedni tydzień',
  month: 'Poprzedni miesiąc',
  custom: 'Poprzedni okres',
}

/** Tydzień temu ma sens tylko tam, gdzie nie jest tym samym co poprzedni okres (tydzień) ani rozjechanym miesiącem. */
export function compareModes(mode: PeriodMode): { value: CompareMode; label: string }[] {
  return [
    { value: 'year', label: 'Rok temu' },
    { value: 'previous', label: PREVIOUS_LABELS[mode] },
    ...(mode === 'day' || mode === 'custom' ? [{ value: 'week' as const, label: 'Tydzień temu' }] : []),
    { value: 'custom', label: 'Własny okres' },
  ]
}

/** Tryb porównania dostępny przy danym okresie; niedostępny wraca do roku temu. */
export function resolveCompareMode(mode: PeriodMode, compare: CompareMode): CompareMode {
  return compareModes(mode).some((option) => option.value === compare) ? compare : 'year'
}

/** Jak nazywamy okres porównywany w nagłówkach kolumn ({@code column}) i w tekście ({@code inline}). */
export const COMPARE_LABELS: Record<CompareMode, { column: string; inline: string }> = {
  year: { column: 'Rok temu', inline: 'rok temu' },
  previous: { column: 'Poprzedni', inline: 'poprzednio' },
  week: { column: 'Tydzień temu', inline: 'tydzień temu' },
  custom: { column: 'Porównywany', inline: 'porównywany' },
}

/** Okres aktualny i okres, z którym go porównujemy. Tydzień i miesiąc w toku kończą się na ostatnim dniu ze sprzedażą. */
export function comparePeriods(
  mode: PeriodMode,
  anchor: string,
  custom: DateRange,
  lastSaleDate: string | null,
  compare: CompareMode,
  compareCustom: DateRange,
): Periods {
  const current = currentPeriod(mode, anchor, custom, lastSaleDate)
  return {
    current,
    previous: comparedPeriod(mode, compare, current, compareCustom),
  }
}

function comparedPeriod(mode: PeriodMode, compare: CompareMode, current: DateRange, compareCustom: DateRange): DateRange {
  if (compare === 'custom') return compareCustom
  if (compare === 'year') return shiftRange(current, -YEAR_AGO_DAYS)
  if (compare === 'week') return shiftRange(current, -7)
  if (mode === 'day') return shiftRange(current, -1)
  if (mode === 'week') return shiftRange(current, -7)
  if (mode === 'month') {
    // Pełny miesiąc z pełnym poprzednim, miesiąc w toku (1-26) z tymi samymi dniami poprzedniego (1-26, najwyżej do jego końca).
    const from = shiftMonth(current.from, -1)
    const end = monthEnd(from)
    if (current.to === monthEnd(current.from)) return { from, to: end }
    const to = addDays(from, dayCount(current) - 1)
    return { from, to: to > end ? end : to }
  }
  // Własny okres: tyle samo dni tuż przed nim.
  return {
    from: addDays(current.from, -dayCount(current)),
    to: addDays(current.from, -1),
  }
}

function shiftRange({ from, to }: DateRange, days: number): DateRange {
  return { from: addDays(from, days), to: addDays(to, days) }
}

/**
 * Rok wcześniej to 52 pełne tygodnie wstecz, a nie ta sama data: poniedziałek-niedziela 19-25.10.2026
 * porównuje się z poniedziałkiem-niedzielą 20-26.10.2025, bo sprzedaż zależy od dnia tygodnia.
 */
export const YEAR_AGO_DAYS = 364

/** Wybrany okres: dzień, pełny tydzień pon.-niedz., miesiąc (w toku do ostatniego dnia ze sprzedażą) albo własny. */
export function currentPeriod(mode: PeriodMode, anchor: string, custom: DateRange, lastSaleDate: string | null): DateRange {
  if (mode === 'day') return { from: anchor, to: anchor }
  if (mode === 'week') {
    // Zawsze pełny tydzień od poniedziałku do niedzieli; który, pilnuje resolveAnchor.
    const from = weekStart(anchor)
    return { from, to: addDays(from, 6) }
  }
  if (mode === 'month') {
    const from = monthStart(anchor)
    return { from, to: clampToData(from, monthEnd(from), lastSaleDate) }
  }
  return custom
}

/** Niedziela ostatniego tygodnia, który ma sprzedaż ze wszystkich siedmiu dni. */
export function lastCompleteWeekEnd(lastSaleDate: string): string {
  const monday = weekStart(lastSaleDate)
  return addDays(monday, 6) === lastSaleDate ? lastSaleDate : addDays(monday, -1)
}

/**
 * Data, od której liczy się okres: najpóźniej ostatni dzień ze sprzedażą. Tydzień porównujemy tylko zakończony: dzień z tygodnia w toku (albo bez
 * wgranej sprzedaży) przesuwa się na ostatni pełny tydzień, pon.-niedz.
 */
export function resolveAnchor(mode: PeriodMode, anchor: string, lastSaleDate: string | null): string {
  if (!lastSaleDate) return anchor
  if (mode !== 'week') return anchor > lastSaleDate ? lastSaleDate : anchor
  const lastSunday = lastCompleteWeekEnd(lastSaleDate)
  return addDays(weekStart(anchor), 6) > lastSunday ? lastSunday : anchor
}

/** Czy strzałka „następny” ma dokąd przejść: do dnia albo miesiąca ze sprzedażą, do tygodnia tylko pełnego. */
export function canShiftForward(mode: Exclude<PeriodMode, 'custom'>, anchor: string, lastSaleDate: string | null) {
  if (!lastSaleDate) return true
  const next = shiftAnchor(mode, anchor, 1)
  return mode === 'week' ? addDays(next, 6) <= lastCompleteWeekEnd(lastSaleDate) : next <= lastSaleDate
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

const withYear = new Intl.DateTimeFormat('pl-PL', {
  weekday: 'short',
  day: 'numeric',
  month: 'short',
  year: 'numeric',
})
const withoutYear = new Intl.DateTimeFormat('pl-PL', {
  weekday: 'short',
  day: 'numeric',
  month: 'short',
})

/**
 * Z dniami tygodnia, bo sprzedaż zależy od dnia tygodnia: "pon., 26 paź 2026",
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
