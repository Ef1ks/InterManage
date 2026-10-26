import { TOOL_ROUTES } from '@/features/tools/categories'

const base = TOOL_ROUTES.DELI_PRICES.path

export const pricePaths = {
  list: base,
  create: `${base}/nowa`,
  calculation: (id: string) => `${base}/${id}`,
}

const money = new Intl.NumberFormat('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const quantity = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 3 })

export function formatMoney(value: number | null | undefined) {
  return value == null ? '-' : `${money.format(value)} zł`
}

export function formatQuantity(value: number) {
  return quantity.format(value)
}

/** Ilość wpisana przez użytkownika: przecinek albo kropka, najwyżej 3 miejsca po przecinku. */
export function parseQuantity(raw: string): { value?: number; error?: string } {
  const text = raw.trim().replace(',', '.')
  if (text === '') return { error: 'Podaj ilość' }
  if (!/^\d+(\.\d+)?$/.test(text)) return { error: 'Wpisz liczbę' }
  if ((text.split('.')[1]?.length ?? 0) > 3) return { error: 'Najwyżej 3 miejsca po przecinku' }
  const value = Number(text)
  if (value <= 0) return { error: 'Więcej niż zero' }
  return { value }
}

/** Cena wpisana ręcznie: przecinek albo kropka, najwyżej 2 miejsca po przecinku, więcej niż zero. */
export function parsePrice(raw: string): { value?: number; error?: string } {
  const text = raw.trim().replace(',', '.')
  if (text === '') return { error: 'Podaj cenę' }
  if (!/^\d+(\.\d+)?$/.test(text)) return { error: 'Wpisz kwotę' }
  if ((text.split('.')[1]?.length ?? 0) > 2) return { error: 'Najwyżej 2 miejsca po przecinku' }
  const value = Number(text)
  if (value <= 0) return { error: 'Więcej niż zero' }
  return { value }
}

const percentFormat = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 1, signDisplay: 'exceptZero' })

/** "+15%", "-3,2%". */
export function formatChange(value: number | null) {
  return value == null ? '' : `${percentFormat.format(value)}%`
}

/** Liczba z kalkulacji do pola edycji: "0,25". */
export function toInputValue(value: number | null | undefined) {
  return value == null ? '' : String(value).replace('.', ',')
}

/** "1 składnik", "3 składniki", "5 składników". */
export function ingredientsWord(count: number) {
  if (count === 1) return 'składnik'
  const lastTwo = count % 100
  const last = count % 10
  return last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? 'składniki' : 'składników'
}

/**
 * Jednostki wagowe - razem ze sztukami o znanej wadze dają wagę surowców przepisu. To waga przed obróbką, więc
 * nie jest wagą gotowego wyrobu (z 3 kg surowców wychodzi np. 1 kg).
 */
export const isWeightUnit = (unit: string) => unit === 'kg'

/** Składnik wpisywany w sztukach (ogórek, sałata) - można mu podać średnią wagę sztuki. */
export const isPieceUnit = (unit: string) => unit === 'szt.'

/** Waga sztuki w kg, opcjonalna: pusty tekst to brak wagi. */
export function parsePieceWeight(raw: string): { value?: number | null; error?: string } {
  if (raw.trim() === '') return { value: null }
  const parsed = parseQuantity(raw)
  return parsed.error ? { error: parsed.error } : { value: parsed.value }
}

/** Narzut, gdy kalkulacja nie jest jeszcze zapisana (zapisana niesie go z serwera). */
export const DEFAULT_MARKUP_PERCENT = 60
export const VAT_RATES = [0, 5, 8, 23] as const
export const DEFAULT_VAT_RATE = 5

/**
 * Cena sprzedaży brutto 1 kg jak na serwerze: koszt + narzut + VAT, zaokrąglona w górę do ,99 zł
 * (15,40 -> 15,99; 16,00 -> 16,99). Pusta, gdy nie ma kosztu.
 */
export function salePrice(costPerKg: number | null, markupPercent: number, vatRate: number): number | null {
  if (costPerKg == null || costPerKg <= 0) return null
  const gross = (costPerKg * (100 + markupPercent) * (100 + vatRate)) / 10_000
  let price = Math.ceil(gross - 1e-9) - 0.01
  if (price + 1e-9 < gross) price += 1
  return Math.round(price * 100) / 100
}

const dayFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short' })

/** "23.10.2026" z daty dokumentu ("2026-10-23T00:00:00", czas sklepu) albo chwili ISO. */
export function formatDay(value: string) {
  return dayFormat.format(new Date(value))
}
