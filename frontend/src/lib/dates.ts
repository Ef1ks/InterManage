/**
 * Daty bez godziny (np. data dostawy) trzymamy jako tekst RRRR-MM-DD, tak jak LocalDate w backendzie.
 * Wszystkie obliczenia w czasie lokalnym przeglądarki, żeby północ nie przesuwała dnia.
 */

export function toIsoDate(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

export function parseIsoDate(value: string): Date {
  const [year, month, day] = value.split('-').map(Number)
  return new Date(year, month - 1, day)
}

export function todayIso(): string {
  return toIsoDate(new Date())
}

export function addDays(value: string, days: number): string {
  const date = parseIsoDate(value)
  date.setDate(date.getDate() + days)
  return toIsoDate(date)
}

const longDate = new Intl.DateTimeFormat('pl-PL', { weekday: 'short', day: 'numeric', month: 'long', year: 'numeric' })
const shortDate = new Intl.DateTimeFormat('pl-PL', { weekday: 'short', day: 'numeric', month: 'short' })

/** "pt., 25 października 2026" */
export function formatLongDate(value: string): string {
  return longDate.format(parseIsoDate(value))
}

/** "pt., 25 paź" */
export function formatShortDate(value: string): string {
  return shortDate.format(parseIsoDate(value))
}

/** Miesiące jako tekst RRRR-MM, tak jak YearMonth w backendzie. */
export function currentMonth(): string {
  return todayIso().slice(0, 7)
}

export function addMonths(month: string, months: number): string {
  const [year, monthNumber] = month.split('-').map(Number)
  const date = new Date(year, monthNumber - 1 + months, 1)
  return toIsoDate(date).slice(0, 7)
}

const monthYear = new Intl.DateTimeFormat('pl-PL', { month: 'long', year: 'numeric' })

/** "Październik 2026" */
export function formatMonth(month: string): string {
  const text = monthYear.format(parseIsoDate(`${month}-01`))
  return text.charAt(0).toUpperCase() + text.slice(1)
}

/** "25.10.2026" - data do wpisywania w polu. */
export function formatTypedDate(value: string): string {
  return value ? `${value.slice(8, 10)}.${value.slice(5, 7)}.${value.slice(0, 4)}` : ''
}

/**
 * Data wpisana ręcznie: 25.10.2026, 25-10-26, 25/10, 25102026, 251026, 2510, samo 25 (bieżący miesiąc) albo
 * 2026-10-25. Brak roku = bieżący rok, rok dwucyfrowy = 20xx. Pusty tekst = pusta data, zły tekst = null.
 */
export function parseTypedDate(text: string): string | null {
  const value = text.trim()
  if (!value) return ''
  const today = todayIso()
  let day: number
  let month: number
  let year: number
  const iso = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(value)
  if (iso) {
    ;[year, month, day] = [Number(iso[1]), Number(iso[2]), Number(iso[3])]
  } else if (/^\d+$/.test(value)) {
    if (value.length <= 2) {
      ;[day, month, year] = [Number(value), Number(today.slice(5, 7)), Number(today.slice(0, 4))]
    } else if (value.length === 4 || value.length === 6 || value.length === 8) {
      day = Number(value.slice(0, 2))
      month = Number(value.slice(2, 4))
      year = value.length === 4 ? Number(today.slice(0, 4)) : Number(value.slice(4))
    } else {
      return null
    }
  } else {
    const parts = value.split(/[.\-/\s,]+/).filter(Boolean)
    if (parts.length < 2 || parts.length > 3 || parts.some((part) => !/^\d+$/.test(part))) return null
    day = Number(parts[0])
    month = Number(parts[1])
    year = parts[2] ? Number(parts[2]) : Number(today.slice(0, 4))
    if (parts[2] && parts[2].length !== 2 && parts[2].length !== 4) return null
  }
  if (year < 100) year += 2000
  const date = new Date(year, month - 1, day)
  if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) return null
  return toIsoDate(date)
}
