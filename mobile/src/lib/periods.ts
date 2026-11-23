import { addDays } from './format'
import { mondayOf, YEAR_AGO_DAYS } from './week'

// Okresy raportów jak `features/sales/periods.ts` w przeglądarce (bez własnego okresu: na telefonie dzień, tydzień, miesiąc).

export type PeriodMode = 'day' | 'week' | 'month'

export type DateRange = { from: string; to: string }
export type Periods = { previous: DateRange; current: DateRange }

export const PERIOD_MODES: { value: PeriodMode; label: string }[] = [
  { value: 'day', label: 'Dzień' },
  { value: 'week', label: 'Tydzień' },
  { value: 'month', label: 'Miesiąc' },
]

export const isPeriodMode = (value: unknown): value is PeriodMode =>
  value === 'day' || value === 'week' || value === 'month'

const monthStart = (value: string) => `${value.slice(0, 7)}-01`

function monthEnd(value: string) {
  const [y, m] = value.split('-').map(Number)
  return new Date(Date.UTC(y, m, 0)).toISOString().slice(0, 10)
}

function shiftMonth(first: string, months: number) {
  const [y, m] = first.split('-').map(Number)
  return new Date(Date.UTC(y, m - 1 + months, 1)).toISOString().slice(0, 10)
}

/** Niedziela ostatniego tygodnia, który ma sprzedaż ze wszystkich siedmiu dni. */
export function lastCompleteWeekEnd(lastSaleDate: string) {
  const monday = mondayOf(lastSaleDate)
  return addDays(monday, 6) === lastSaleDate ? lastSaleDate : addDays(monday, -1)
}

/** Najpóźniej ostatni dzień ze sprzedażą; tydzień tylko zakończony (pon.-niedz.). */
export function resolveAnchor(mode: PeriodMode, anchor: string, lastSaleDate: string | null) {
  if (!lastSaleDate) return anchor
  if (mode !== 'week') return anchor > lastSaleDate ? lastSaleDate : anchor
  const lastSunday = lastCompleteWeekEnd(lastSaleDate)
  return addDays(mondayOf(anchor), 6) > lastSunday ? lastSunday : anchor
}

/** Dzień, pełny tydzień pon.-niedz. albo miesiąc (w toku ucięty do ostatniego dnia ze sprzedażą). */
export function currentPeriod(mode: PeriodMode, anchor: string, lastSaleDate: string | null): DateRange {
  if (mode === 'day') return { from: anchor, to: anchor }
  if (mode === 'week') {
    const from = mondayOf(anchor)
    return { from, to: addDays(from, 6) }
  }
  const from = monthStart(anchor)
  const end = monthEnd(from)
  return { from, to: lastSaleDate && lastSaleDate >= from && lastSaleDate < end ? lastSaleDate : end }
}

export const yearAgoRange = ({ from, to }: DateRange): DateRange => ({
  from: addDays(from, -YEAR_AGO_DAYS),
  to: addDays(to, -YEAR_AGO_DAYS),
})

/** Okres aktualny i ten sam okres rok wcześniej (52 tygodnie wstecz, te same dni tygodnia), jak domyślnie w przeglądarce. */
export function yearPeriods(mode: PeriodMode, anchor: string, lastSaleDate: string | null): Periods {
  const current = currentPeriod(mode, anchor, lastSaleDate)
  return { current, previous: yearAgoRange(current) }
}

export function shiftAnchor(mode: PeriodMode, anchor: string, step: -1 | 1) {
  if (mode === 'day') return addDays(anchor, step)
  if (mode === 'week') return addDays(mondayOf(anchor), 7 * step)
  return shiftMonth(monthStart(anchor), step)
}

export function canShiftForward(mode: PeriodMode, anchor: string, lastSaleDate: string | null) {
  if (!lastSaleDate) return true
  const next = shiftAnchor(mode, anchor, 1)
  return mode === 'week' ? addDays(next, 6) <= lastCompleteWeekEnd(lastSaleDate) : next <= lastSaleDate
}

const MONTHS = [
  'styczeń',
  'luty',
  'marzec',
  'kwiecień',
  'maj',
  'czerwiec',
  'lipiec',
  'sierpień',
  'wrzesień',
  'październik',
  'listopad',
  'grudzień',
]

const dm = (iso: string) => `${iso.slice(8, 10)}.${iso.slice(5, 7)}`
const dmy = (iso: string) => `${dm(iso)}.${iso.slice(0, 4)}`

/** Okres w nagłówku: 21.11.2026, 16.11 – 22.11.2026, listopad 2026 (do 21.11). */
export function periodLabel(mode: PeriodMode, range: DateRange) {
  if (mode === 'day' || range.from === range.to) return dmy(range.from)
  if (mode === 'month') {
    const name = `${MONTHS[Number(range.from.slice(5, 7)) - 1]} ${range.from.slice(0, 4)}`
    return range.to === monthEnd(range.from) ? name : `${name} (do ${dm(range.to)})`
  }
  return `${dm(range.from)} – ${dmy(range.to)}`
}

export const rangeLabel = (range: DateRange) =>
  range.from === range.to ? dmy(range.from) : `${dm(range.from)} – ${dmy(range.to)}`
