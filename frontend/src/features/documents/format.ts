import { TOOL_ROUTES } from '@/features/tools/categories'

const base = TOOL_ROUTES.DOCUMENTS.path

/** Kod typu ("PZ") trafia do ścieżki małymi literami: /dokumenty/pz/123. */
export const documentPaths = {
  overview: base,
  list: (typeCode: string) => `${base}/${typeCode.toLowerCase()}`,
  document: (typeCode: string, id: string) => `${base}/${typeCode.toLowerCase()}/${id}`,
}

const money = new Intl.NumberFormat('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const price = new Intl.NumberFormat('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 4 })
const quantity = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 3 })
const percent = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 2 })
export const countFormat = new Intl.NumberFormat('pl-PL')

/** Kwota w złotych z dwoma miejscami ("1 049,40"); kolumny mają "zł" w nagłówku. */
export function formatMoney(value: number | null | undefined) {
  return value == null ? '' : money.format(value)
}

/** Cena jednostkowa: do 4 miejsc, bo ceny zakupu FIFO bywają dokładniejsze niż grosz. */
export function formatPrice(value: number | null | undefined) {
  return value == null ? '' : price.format(value)
}

export function formatPercent(value: number | null | undefined) {
  return value == null ? '' : `${percent.format(value)}%`
}

/**
 * Sama liczba, bez jednostki: jednostka z raportu ("kg", "L") opisuje produkt, a waga czy pojemność
 * są już w jego nazwie ("SPODY WIENER 400G" przyjęte jako 8 sztuk, nie 8 kg).
 */
export function formatQuantity(value: number) {
  return quantity.format(value)
}

/** "2026-10-21T00:00:00" -> "21.10.2026"; godzinę pokazujemy tylko, gdy raport ją podał. */
export function formatDocumentDate(value: string) {
  const [date, time = '00:00:00'] = value.split('T')
  const [year, month, day] = date.split('-')
  const formatted = `${day}.${month}.${year}`
  return time.startsWith('00:00:00') ? formatted : `${formatted}, ${time.slice(0, 5)}`
}

/** "1 dokument", "3 dokumenty", "5 dokumentów". */
export function documentsWord(count: number) {
  if (count === 1) return 'dokument'
  const lastTwo = count % 100
  const last = count % 10
  return last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? 'dokumenty' : 'dokumentów'
}
