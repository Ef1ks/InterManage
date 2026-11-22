import type { MacroReport } from '@/features/imports/import-sources'

/*
 * Makro zapisu raportów z Mercalys składa serwer (`ReportSaveMacro`, `GET .../imports/report-macro`, to samo dostaje
 * agent sklepu przy aktualizacji danych po starcie komputera). Tu zostały tylko zasady pól w ustawieniach importu.
 */

/** Raporty drukowane z Mercalys tylko do PDF. */
const PDF_REPORTS: readonly MacroReport[] = ['PROMOTIONS']

export type ReportMacroSettings = {
  path: string
  /** Numer wydruku ustawialnego w Mercalys (Baza danych). */
  printNumber?: string
  /** Numer listy artykułów w Mercalys (Obecne w cadencier). */
  listNumber?: string
  /** Długość części zaległego okresu (sprzedaż, dokumenty); puste = limit Mercalys. */
  partLength?: string
}

/** Raporty, których wydruk w Mercalys ma numer ustawiany w ustawieniach importu. */
export const PRINT_NUMBER_REPORTS: readonly MacroReport[] = ['PRODUCTS', 'CADENCIER']

/** Raporty z listy artykułów („Innych list…”), której numer ustawia się w ustawieniach importu. */
export const LIST_NUMBER_REPORTS: readonly MacroReport[] = ['CADENCIER']

export function printNumberError(value: string): string | null {
  const number = value.trim()
  if (!number) return 'Wpisz numer wydruku ustawialnego z Mercalys'
  return /^\d{1,2}$/.test(number) ? null : 'Numer wydruku to 1 albo 2 cyfry'
}

export function listNumberError(value: string): string | null {
  const number = value.trim()
  if (!number) return 'Wpisz numer listy z Mercalys'
  return /^\d{1,4}$/.test(number) ? null : 'Numer listy to od 1 do 4 cyfr'
}

/** Przykładowa ścieżka w podpowiedzi pola zapisu. */
export const pathExample = (kind: MacroReport) =>
  PDF_REPORTS.includes(kind) ? 'P:\\Raporty\\taryfy.pdf' : 'P:\\Raporty\\baza.xlsx'
