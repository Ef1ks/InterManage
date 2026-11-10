import type { DataEntryRow } from './rows'

export type PluItem = { ean: string; name: string }

/**
 * Nowe artykuły z kodem wagi 24 (pełny EAN, 13 cyfr), każdy raz: tylko one potrzebują kodu PLU, artykuły z kartoteki
 * już go mają. Kody zastępcze z faktury się nie liczą.
 */
export function pluItems(rows: Pick<DataEntryRow, 'ean' | 'eanFallback' | 'name' | 'known'>[]): PluItem[] {
  const items = new Map<string, PluItem>()
  for (const row of rows) {
    if (row.known || row.eanFallback || !/^24\d{11}$/.test(row.ean) || items.has(row.ean)) continue
    items.set(row.ean, { ean: row.ean, name: row.name })
  }
  return [...items.values()]
}
