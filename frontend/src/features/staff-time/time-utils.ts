import { addDays, parseIsoDate, toIsoDate } from '@/lib/dates'
import type { TimeCardRow, TimeDayCard, TimeSegment, TimeSegmentStatus } from '@/lib/types'

export type TimeView = 'dzien' | 'tydzien' | 'miesiac'

/** Minuty jako godziny z minutami: 480 → 8:00, 5 → 0:05. */
export function hm(minutes: number) {
  const abs = Math.abs(Math.round(minutes))
  return `${minutes < 0 ? '-' : ''}${Math.floor(abs / 60)}:${String(abs % 60).padStart(2, '0')}`
}

/** 2026-12-01T05:55:00 → 05:55. */
export const clockOf = (dateTime: string) => dateTime.slice(11, 16)

/** Minuty od północy dnia karty (wyjście po północy daje ponad 24 h). */
export function minuteOf(dateTime: string, day: string) {
  const days = Math.round((parseIsoDate(dateTime.slice(0, 10)).getTime() - parseIsoDate(day).getTime()) / 86_400_000)
  return days * 1440 + Number(dateTime.slice(11, 13)) * 60 + Number(dateTime.slice(14, 16))
}

export function range(view: TimeView, date: string): { from: string; to: string } {
  if (view === 'dzien') return { from: date, to: date }
  if (view === 'tydzien') {
    const weekday = (parseIsoDate(date).getDay() + 6) % 7
    const from = addDays(date, -weekday)
    return { from, to: addDays(from, 6) }
  }
  const first = `${date.slice(0, 7)}-01`
  const parsed = parseIsoDate(first)
  return { from: first, to: toIsoDate(new Date(parsed.getFullYear(), parsed.getMonth() + 1, 0)) }
}

export function shift(view: TimeView, date: string, step: number) {
  if (view === 'dzien') return addDays(date, step)
  if (view === 'tydzien') return addDays(date, step * 7)
  const parsed = parseIsoDate(`${date.slice(0, 7)}-01`)
  return toIsoDate(new Date(parsed.getFullYear(), parsed.getMonth() + step, 1))
}

/** Odcinki, o których decyduje kierownik (z tolerancją nie czekają). */
export const decidable = (segment: TimeSegment) =>
  (segment.part === 'OVERTIME' || segment.part === 'SHORTFALL') && !segment.status.startsWith('TOLERATED')

export const isOffset = (segment: TimeSegment) =>
  segment.status === 'OFFSET_SHORTFALL' || segment.status === 'OFFSET_OVERTIME'

/** Kolory stanu odcinka (mocne odcienie, każdy z wariantem ciemnym). */
export const STATUS_STYLES: Record<TimeSegmentStatus, { label: string; bar: string }> = {
  OK: { label: 'Praca w grafiku', bar: 'bg-emerald-400 dark:bg-emerald-600' },
  BREAK: { label: 'Przerwa niepłatna', bar: 'bg-zinc-300 dark:bg-zinc-600' },
  TOLERATED_OVERTIME: {
    label: 'Poza grafikiem w tolerancji (nie liczy się)',
    bar: 'bg-zinc-300 dark:bg-zinc-600',
  },
  TOLERATED_SHORTFALL: {
    label: 'Brak w tolerancji (liczy się jak w grafiku)',
    bar: 'bg-emerald-200 dark:bg-emerald-800',
  },
  PENDING: { label: 'Poza grafikiem: czeka na decyzję', bar: 'bg-amber-400 dark:bg-amber-500' },
  EMPLOYER_ORDER: { label: 'Nadgodziny na polecenie pracodawcy', bar: 'bg-sky-400 dark:bg-sky-600' },
  EMPLOYEE_REQUEST: { label: 'Nadgodziny na wniosek pracownika', bar: 'bg-indigo-400 dark:bg-indigo-500' },
  REJECTED: { label: 'Odrzucone: własna inicjatywa (nie liczy się)', bar: 'bg-zinc-400 dark:bg-zinc-500' },
  UNEXCUSED: { label: 'Brak czasu z grafiku (spóźnienie, wyjście)', bar: 'bg-red-400 dark:bg-red-600' },
  JUSTIFIED: { label: 'Brak usprawiedliwiony (liczy się)', bar: 'bg-violet-400 dark:bg-violet-500' },
  OFFSET_SHORTFALL: {
    label: 'Brak zrównoważony dłuższą pracą (liczy się jak w grafiku)',
    bar: 'bg-teal-300 dark:bg-teal-700',
  },
  OFFSET_OVERTIME: {
    label: 'Dłuższa praca równoważąca brak (nie nadgodziny)',
    bar: 'bg-teal-500 dark:bg-teal-500',
  },
}

export const LEGEND: TimeSegmentStatus[] = [
  'OK',
  'PENDING',
  'EMPLOYER_ORDER',
  'EMPLOYEE_REQUEST',
  'REJECTED',
  'UNEXCUSED',
  'JUSTIFIED',
  'OFFSET_SHORTFALL',
  'OFFSET_OVERTIME',
  'BREAK',
]

/** Wiersze podzielone na stanowiska, jak grafik bez własnych grup (serwer układa je po stanowisku i nazwisku). */
/** Klucz wiersza: osoba z umową o pracę i zleceniem ma dwa wiersze. */
export const rowKey = (row: TimeCardRow) => (row.civil ? `${row.employeeId}:zlecenie` : row.employeeId)

export function byPosition<T extends { positionName: string }>(rows: T[]) {
  const groups: { position: string; rows: T[] }[] = []
  for (const row of rows) {
    const last = groups.at(-1)
    if (last?.position === row.positionName) last.rows.push(row)
    else groups.push({ position: row.positionName, rows: [row] })
  }
  return groups
}

/** Dzień, w którym brak i czas poza grafikiem czekają, więc da się je zrównoważyć. */
export const canBalance = (day: TimeDayCard) =>
  day.segments.some((segment) => segment.status === 'UNEXCUSED') &&
  day.segments.some((segment) => segment.status === 'PENDING')

/** Tło komórki tygodnia i miesiąca: najpierw to, co wymaga działania. */
export function dayTone(day: TimeDayCard) {
  if (day.pending > 0) return 'bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-50'
  if (day.missingExit || day.shortfall > 0) return 'bg-red-200 text-red-950 dark:bg-red-900/70 dark:text-red-50'
  if (day.overtime > 0) return 'bg-sky-200 text-sky-950 dark:bg-sky-900/70 dark:text-sky-50'
  if (day.worked > 0) return 'bg-emerald-100 text-emerald-950 dark:bg-emerald-950/60 dark:text-emerald-50'
  return ''
}
