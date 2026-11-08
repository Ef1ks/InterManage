import { formatMacroQuantity } from './correction-macro'
import { transferEanLine, transferFinishLines, transferQuantityLine, transferStartLines } from './mercalys-macro'
import type { DataEntryRow } from './rows'

/**
 * Makro „Przeniesienie” dla odtwarzacza AutoHotkey (InterClicker, Alt+E czyta schowek linia po linii, jak „Makro korekty”):
 * od otwarcia Mercalys, przez Przesunięcie stanu i wszystkie artykuły (EAN, Enter, ilość, Enter), do przycisku
 * przeniesienia, dwóch pytań i schowania programu. Czasy w ms, poprawiamy je tylko tutaj.
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
 * zastępczym z faktury, z kodem innym niż do 13 cyfr (tyle mieści pole) albo bez ilości: kolejne klawisze trafiłyby
 * w złe pola.
 */
export function planTransferMacro(rows: Pick<DataEntryRow, 'ean' | 'eanFallback' | 'quantity'>[]) {
  const items: TransferMacroItem[] = []
  for (const row of rows) {
    if (/^\d{1,13}$/.test(row.ean) && !row.eanFallback && row.quantity !== null) {
      items.push({ ean: row.ean, quantity: row.quantity })
    }
  }
  return { items, skipped: rows.length - items.length }
}

/** Skrypt makra; linie z `#` odtwarzacz pomija. CRLF jak w Windows. */
export function buildTransferMacro(items: TransferMacroItem[]) {
  const c = TRANSFER_MACRO_CONFIG
  const out = [`# Przeniesienie: ${items.length} poz.`, ...transferStartLines()]
  for (const item of items) {
    out.push(
      `# EAN ${item.ean}`,
      transferEanLine(item.ean),
      `SLEEP|${c.afterEan}`,
      transferQuantityLine(formatMacroQuantity(item.quantity)),
      `SLEEP|${c.afterQuantity}`,
    )
  }
  out.push(...transferFinishLines())
  return out.join('\r\n') + '\r\n'
}
