import type { CashDocumentKind } from '@/lib/types'

const money = new Intl.NumberFormat('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

export function formatMoney(value: number): string {
  return `${money.format(value)} zł`
}

/** Kwota wpisana w polu (przecinek albo kropka, najwyżej 2 miejsca); `null` = niepoprawna. */
export function parseAmount(text: string): number | null {
  const normalized = text.replace(/\s/g, '').replace(',', '.')
  if (!/^\d+(\.\d{1,2})?$/.test(normalized)) return null
  return Number(normalized)
}

export const KIND_LABELS: Record<CashDocumentKind, string> = {
  KP: 'Dowód wpłaty',
  KW: 'Dowód wypłaty',
}

/** Najczęstsze operacje „Za co” z dotychczasowego arkusza; można wpisać też inną. */
export const OPERATIONS: Record<CashDocumentKind, string[]> = {
  KP: ['Utarg z dnia', 'Wpłata gotówki', 'Zwrot zaliczki'],
  KW: ['Zdanie utargu do banku', 'Zwrot gotówki', 'Zaliczka', 'Zakup za gotówkę'],
}

/** KP zielone (wpływ), KW bursztynowe (wypłata); mocne odcienie jak w reszcie oznaczeń. */
export const KIND_STYLES: Record<CashDocumentKind, string> = {
  KP: 'bg-emerald-200 text-emerald-950 dark:bg-emerald-900/70 dark:text-emerald-100',
  KW: 'bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100',
}

const dateTime = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short', timeStyle: 'short' })

export function formatDateTime(value: string): string {
  return dateTime.format(new Date(value))
}
