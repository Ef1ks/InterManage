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
