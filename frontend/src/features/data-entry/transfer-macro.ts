import { formatMacroQuantity } from './correction-macro'
import type { DataEntryRow } from './rows'

/**
 * Makro „Przeniesienie” dla odtwarzacza AutoHotkey (InterClicker, F9 czyta schowek linia po linii, jak „Makro korekty”):
 * dla każdego artykułu EAN, Enter, ilość, Enter. Czasy w ms, poprawiamy je tylko tutaj.
 */
export const TRANSFER_MACRO_CONFIG = {
  /** Po EAN-ie program ładuje artykuł: bez przerwy wpisana ilość mogłaby przepaść. */
  afterEan: 300,
  /** Po ilości, przed kolejnym EAN-em. */
  afterQuantity: 300,
} as const

export type TransferMacroItem = { ean: string; quantity: number }

/**
 * EAN-y i ilości wprost z tabeli, po kolei, bez łączenia i zmiany znaku. Pominięty jest tylko wiersz bez EAN-u, z kodem
 * zastępczym z faktury albo bez ilości: kolejne klawisze trafiłyby w złe pola.
 */
export function planTransferMacro(rows: Pick<DataEntryRow, 'ean' | 'eanFallback' | 'quantity'>[]) {
  const items: TransferMacroItem[] = []
  for (const row of rows) {
    if (row.ean !== '' && !row.eanFallback && row.quantity !== null) {
      items.push({ ean: row.ean, quantity: row.quantity })
    }
  }
  return { items, skipped: rows.length - items.length }
}

/** Skrypt makra; linie z `#` odtwarzacz pomija. CRLF jak w Windows. */
export function buildTransferMacro(items: TransferMacroItem[]) {
  const c = TRANSFER_MACRO_CONFIG
  const out = [`# Przeniesienie: ${items.length} poz.`]
  for (const item of items) {
    out.push(
      `TEXT|${item.ean}`,
      'KEY|{Enter}',
      `SLEEP|${c.afterEan}`,
      `TEXT|${formatMacroQuantity(item.quantity)}`,
      'KEY|{Enter}',
      `SLEEP|${c.afterQuantity}`,
    )
  }
  return out.join('\r\n') + '\r\n'
}
