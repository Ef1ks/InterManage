import { TOOL_ROUTES } from '@/features/tools/categories'
import { parseIsoDate } from '@/lib/dates'

const base = TOOL_ROUTES.KSEF_INVOICES.path

export const ksefPaths = {
  invoices: base,
  settings: `${base}/ustawienia`,
}

const dateTime = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short', timeStyle: 'short' })
const day = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short' })

/** "25.10.2026, 14:05" z chwili ISO. */
export function formatDateTime(value: string) {
  return dateTime.format(new Date(value))
}

/** "25.10.2026" z chwili ISO. */
export function formatDay(value: string) {
  return day.format(new Date(value))
}

/** Data bez godziny (RRRR-MM-DD, np. wystawienia faktury) jako "19.10.2026". */
export function formatDate(value: string) {
  return day.format(parseIsoDate(value))
}

/** NIP do czytania: "526-587-76-35". */
export function formatNip(nip: string) {
  return nip.length === 10 ? `${nip.slice(0, 3)}-${nip.slice(3, 6)}-${nip.slice(6, 8)}-${nip.slice(8)}` : nip
}

/** "1 faktura", "3 faktury", "5 faktur". */
export function invoicesWord(count: number) {
  if (count === 1) return 'faktura'
  const lastTwo = count % 100
  const last = count % 10
  return last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? 'faktury' : 'faktur'
}

/** Rodzaje faktur z KSeF po polsku (kody zostają w podpowiedzi). */
const INVOICE_TYPES: Record<string, string> = {
  Vat: 'Faktura',
  Kor: 'Korekta',
  Zal: 'Zaliczkowa',
  Roz: 'Rozliczeniowa',
  Upr: 'Uproszczona',
  KorZal: 'Korekta zaliczkowej',
  KorRoz: 'Korekta rozliczeniowej',
}

export function invoiceTypeLabel(type: string) {
  return INVOICE_TYPES[type] ?? type
}
