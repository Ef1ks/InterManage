import { parseIsoDate } from '@/lib/dates'
import type { ScheduleCell, SchedulePosition, ScheduleRow, StaffDay } from '@/lib/types'
import { distinctHours, type ShiftHours } from '@/features/staff/shifts'

const WEEK: StaffDay[] = ['SUNDAY', 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY']
const WEEK_LABELS = ['nd', 'pn', 'wt', 'śr', 'cz', 'pt', 'sb']

export const dayOfWeek = (date: string) => WEEK[parseIsoDate(date).getDay()]
export const weekdayLabel = (date: string) => WEEK_LABELS[parseIsoDate(date).getDay()]
export const dayNumber = (date: string) => Number(date.slice(8))

/** 06:00 → 6, 07:30 → 7:30: w komórce godziny stoją jedna pod drugą. */
export function clock(time: string) {
  const [hours, minutes] = time.split(':')
  return minutes === '00' ? String(Number(hours)) : `${Number(hours)}:${minutes}`
}

/** Godziny z minutami, żeby było widać każdą minutę: 480 → 8:00, 306 → 5:06, -45 → -0:45. */
export function hoursLabel(minutes: number) {
  const rounded = Math.round(minutes)
  const abs = Math.abs(rounded)
  return `${rounded < 0 ? '-' : ''}${Math.floor(abs / 60)}:${String(abs % 60).padStart(2, '0')}`
}

const dateTime = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short', timeStyle: 'short' })
export const formatDateTime = (value: string) => dateTime.format(new Date(value))

const shortDate = new Intl.DateTimeFormat('pl-PL', { day: 'numeric', month: 'long' })
const numericDate = new Intl.DateTimeFormat('pl-PL', { day: 'numeric', month: 'numeric', year: 'numeric' })
export const formatPeriodDay = (date: string) => numericDate.format(parseIsoDate(date))

export const formatDay = (date: string) => `${weekdayLabel(date)}, ${shortDate.format(parseIsoDate(date))}`

/** 2026-12-06 → 6.12 */
export const dayMonth = (date: string) => `${Number(date.slice(8))}.${date.slice(5, 7)}`

export const isSunday = (date: string) => parseIsoDate(date).getDay() === 0

/** Za co przysługuje dzień wolny, pełnym zdaniem. */
export function dayOffReason(cell: Pick<ScheduleCell, 'dayOff' | 'forDate'>) {
  if (cell.dayOff === 'W5') return 'W5: wolne z tytułu pięciodniowego tygodnia pracy'
  if (cell.dayOff === 'W') return 'W: dzień wolny'
  if (cell.dayOff !== 'WN' || !cell.forDate) return null
  return `WN: wolne za pracę ${isSunday(cell.forDate) ? 'w niedzielę' : 'w święto'} ${dayMonth(cell.forDate)}`
}

export const daysBetween = (first: string, second: string) =>
  Math.round(Math.abs(parseIsoDate(first).getTime() - parseIsoDate(second).getTime()) / 86_400_000)

export const cellsByDate = (row: ScheduleRow) => new Map(row.cells.map((cell) => [cell.date, cell]))

export const ABSENCE: Record<NonNullable<ScheduleCell['absence']>, { symbol: string; label: string }> = {
  ANNUAL: { symbol: 'U', label: 'Urlop wypoczynkowy' },
  ON_DEMAND: { symbol: 'UŻ', label: 'Urlop na żądanie' },
  CHILD_CARE: { symbol: 'OD', label: 'Opieka nad dzieckiem' },
  FORCE_MAJEURE: { symbol: 'SW', label: 'Zwolnienie z powodu siły wyższej' },
  CARE: { symbol: 'UO', label: 'Urlop opiekuńczy' },
  REHABILITATION: { symbol: 'TR', label: 'Turnus rehabilitacyjny' },
  OCCASIONAL: { symbol: 'OK', label: 'Urlop okolicznościowy' },
  UNPAID: { symbol: 'UB', label: 'Urlop bezpłatny' },
  OTHER: { symbol: 'N', label: 'Nieobecność (rodzaj widać w Kadrach)' },
}

/** Skrót do komórki i pełna nazwa nieobecności do podpowiedzi. */
export function absenceOf(cell: ScheduleCell) {
  return ABSENCE[cell.absence ?? 'OTHER']
}

/** Miesiąc ponad celem albo nadgodziny dobowe do odebrania: do wyrównania w grafiku. */
export function needsBalancing(row: ScheduleRow) {
  const over = row.monthTargetMinutes !== null && (row.monthMinutes ?? 0) > row.monthTargetMinutes
  return over || (row.toCompensateMinutes ?? 0) > 0
}

const pln = new Intl.NumberFormat('pl-PL', { style: 'currency', currency: 'PLN' })
export const formatPay = (value: number) => pln.format(value)

/** Minuty zmiany GG:MM-GG:MM; koniec przed początkiem to zmiana przez północ. */
export function shiftMinutes(start: string, end: string) {
  const toMinutes = (value: string) => Number(value.slice(0, 2)) * 60 + Number(value.slice(3, 5))
  const minutes = toMinutes(end) - toMinutes(start)
  return minutes <= 0 ? minutes + 24 * 60 : minutes
}

/** Rano, popołudnie i inny sklep mają inny kolor, żeby rozkład zmian było widać bez czytania liczb. */
export function cellKind(cell: ScheduleCell | undefined) {
  if (!cell) return 'empty'
  // Dzień wolny pod urlopem nie jest dniem urlopu (art. 154² KP), więc komórka pokazuje dzień wolny.
  if (cell.dayOff) return cell.dayOff === 'WN' ? 'wn' : cell.dayOff === 'W5' ? 'w5' : 'w'
  if (cell.absent) return cell.absence === 'ANNUAL' || cell.absence === 'ON_DEMAND' ? 'leave' : 'absent'
  // Sam czas wolny za nadgodziny, bez zmiany: dzień (albo jego część) wolny w zamian za nadgodziny.
  if (!cell.start) return 'timeoff'
  if (cell.store) return 'store'
  return (cell.start ?? '') < '12:00' ? 'morning' : 'afternoon'
}

/** Godziny do wyboru danego dnia: stanowiska w tym sklepie, potem stałe pracownika. */
export function hoursFor(row: ScheduleRow, position: SchedulePosition | undefined, date: string): ShiftHours[] {
  const day = dayOfWeek(date)
  const shifts = [...(position?.shifts ?? []), ...row.fixedShifts].filter((shift) => shift.dayOfWeek === day)
  return distinctHours(shifts)
}

/** Godziny do szybkiego wpisywania: wszystkie z działu, bez względu na dzień tygodnia. */
export function brushHours(positions: SchedulePosition[], rows: ScheduleRow[]): ShiftHours[] {
  return distinctHours([...positions.flatMap((position) => position.shifts), ...rows.flatMap((row) => row.fixedShifts)])
}
