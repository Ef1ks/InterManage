import { addDays, parseIsoDate, toIsoDate } from './dates'

/**
 * Święta ustawowe (ustawa o dniach wolnych od pracy, ta sama lista co `ScheduleCalendar.holiday` w backendzie) i dni
 * przed nimi. Przy porównaniu sprzedaży mówią, dlaczego dzień rok temu albo teraz odbiega od zwykłego: dzień przed
 * świętem ma większy ruch, a w święto sklep jest zwykle zamknięty.
 */

/** Niedziela wielkanocna (algorytm Meeusa/Jonesa/Butchera). */
function easter(year: number): string {
  const a = year % 19
  const b = Math.floor(year / 100)
  const c = year % 100
  const d = Math.floor(b / 4)
  const e = b % 4
  const f = Math.floor((b + 8) / 25)
  const g = Math.floor((b - f + 1) / 3)
  const h = (19 * a + b - d - g + 15) % 30
  const i = Math.floor(c / 4)
  const k = c % 4
  const l = (32 + 2 * e + 2 * i - h - k) % 7
  const m = Math.floor((a + 11 * h + 22 * l) / 451)
  const month = Math.floor((h + l - 7 * m + 114) / 31)
  const day = ((h + l - 7 * m + 114) % 31) + 1
  return toIsoDate(new Date(year, month - 1, day))
}

const FIXED: Record<string, string> = {
  '01-01': 'Nowy Rok',
  '01-06': 'Trzech Króli',
  '05-01': 'Święto Pracy',
  '05-03': 'Święto Konstytucji 3 Maja',
  '08-15': 'Wniebowzięcie NMP',
  '11-01': 'Wszystkich Świętych',
  '11-11': 'Święto Niepodległości',
  '12-25': 'Boże Narodzenie',
  '12-26': 'Drugi dzień Bożego Narodzenia',
}

/** Wigilia jest dniem wolnym od 2025 roku. */
const CHRISTMAS_EVE_FROM = 2025

/** Nazwa święta albo `null`. Zwykła niedziela nie jest świętem. */
export function holidayName(date: string): string | null {
  const year = parseIsoDate(date).getFullYear()
  const fixed = FIXED[date.slice(5)]
  if (fixed) return fixed
  if (date.slice(5) === '12-24' && year >= CHRISTMAS_EVE_FROM) return 'Wigilia'
  const sunday = easter(year)
  if (date === sunday) return 'Wielkanoc'
  if (date === addDays(sunday, 1)) return 'Poniedziałek Wielkanocny'
  if (date === addDays(sunday, 49)) return 'Zielone Świątki'
  if (date === addDays(sunday, 60)) return 'Boże Ciało'
  return null
}

export type SpecialDay = { kind: 'holiday' | 'eve'; holiday: string }

/** Święto albo dzień przed świętem; święto ma pierwszeństwo (24.12 to Wigilia, nie dzień przed Bożym Narodzeniem). */
export function specialDay(date: string): SpecialDay | null {
  const holiday = holidayName(date)
  if (holiday) return { kind: 'holiday', holiday }
  const next = holidayName(addDays(date, 1))
  return next ? { kind: 'eve', holiday: next } : null
}

/** "święto (Wszystkich Świętych)", "dzień przed świętem (Święto Niepodległości)" */
export function specialDayLabel(day: SpecialDay): string {
  return day.kind === 'holiday' ? `święto (${day.holiday})` : `dzień przed świętem (${day.holiday})`
}

/** Szczególne dni okresu, od najwcześniejszego. */
export function specialDaysIn(range: { from: string; to: string }): { date: string; day: SpecialDay }[] {
  const days: { date: string; day: SpecialDay }[] = []
  for (let date = range.from; date <= range.to; date = addDays(date, 1)) {
    const day = specialDay(date)
    if (day) days.push({ date, day })
  }
  return days
}
