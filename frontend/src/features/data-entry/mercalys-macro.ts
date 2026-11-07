import { addDays, toIsoDate, todayIso } from '@/lib/dates'
import type { DataEntryXmlExport } from '@/lib/types'

/** Ekran „Obsługa BL Bezpośrednich” w Mercalys: identyfikatory z kodu strony, poprawiamy je tylko tutaj. */
export const MERCALYS_BL_SCREEN = {
  window: 'Mercalys',
  grid: 'dgDetail',
  /** Kolumna Nr Zamów, od zera: pusta = bon bez kodu. */
  orderColumn: 10,
  createCodeButton: 'btnB4_CreationCommande_LnkBouton',
  nextPageButton: 'pgrDetail_ibtSuivant',
} as const

/** Bon do makra: dokument z pliku XML zapisanego we wprowadzaniu danych. */
export type MacroDocument = {
  key: string
  number: string
  /** Kod dostawcy z nazwy pliku; pusty, gdy plik ma NIP zamiast kodu, wtedy makro nie sprawdza dostawcy. */
  supplierCode: string
  supplierName: string | null
  documentDate: string
  netValue: number
  savedDay: string
}

/** Nazwa pliku to `<nr sklepu>_<kod dostawcy albo NIP>_<nazwa>_<nr faktury>.xml`; NIP ma 10 cyfr, kod mniej. */
export const supplierCodeFromFileName = (fileName: string) => /^\d+_(\d{1,9})_/.exec(fileName)?.[1] ?? ''

/** Dokumenty z historii, od najnowszego; plik zapisany kilka razy to jeden bon. */
export function macroDocuments(exports: DataEntryXmlExport[]): MacroDocument[] {
  const documents = new Map<string, MacroDocument>()
  for (const item of exports) {
    if (!item.documentNumber) continue
    const supplierCode = supplierCodeFromFileName(item.fileName)
    const key = `${supplierCode}|${item.documentNumber}`
    if (documents.has(key)) continue
    documents.set(key, {
      key,
      number: item.documentNumber,
      supplierCode,
      supplierName: item.supplierName,
      documentDate: item.documentDate,
      netValue: item.netValue,
      savedDay: toIsoDate(new Date(item.createdAt)),
    })
  }
  return [...documents.values()]
}

/** Czy dokument zapisano w ostatnich dniach; `null` = wszystkie, 1 = dziś. */
export const savedWithin = (document: MacroDocument, days: number | null) =>
  days === null || document.savedDay >= addDays(todayIso(), 1 - days)

/**
 * Makro dla odtwarzacza: lista bonów (WEBLIST) i tworzenie kodu tylko na bonach z tej listy bez Nr Zamów (WEBROWS).
 * Odtwarzacz kończy podsumowaniem Nr BL -> Nr Zamów i listą bonów, których nie znalazł. CRLF jak w Windows.
 */
export function buildCreateCodesMacro(documents: MacroDocument[]) {
  const s = MERCALYS_BL_SCREEN
  return (
    [
      `# Tworzenie kodu, bonów: ${documents.length}`,
      ...documents.map((document) => `WEBLIST|${document.supplierCode}|${document.number}`),
      `WEBROWS|${s.window}|${s.grid}|${s.orderColumn}|${s.createCodeButton}|${s.nextPageButton}|${documents.length}`,
    ].join('\r\n') + '\r\n'
  )
}
