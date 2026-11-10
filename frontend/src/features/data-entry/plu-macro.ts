import type { DataEntryRow } from './rows'

export type PluItem = { ean: string; name: string; isNew: boolean }

/** Kody wagi 24 z pełnym EAN-em (13 cyfr), każdy raz; kody zastępcze z faktury się nie liczą. */
export function pluItems(rows: Pick<DataEntryRow, 'ean' | 'eanFallback' | 'name' | 'known'>[]): PluItem[] {
  const items = new Map<string, PluItem>()
  for (const row of rows) {
    if (row.eanFallback || !/^24\d{11}$/.test(row.ean) || items.has(row.ean)) continue
    items.set(row.ean, { ean: row.ean, name: row.name, isNew: !row.known })
  }
  return [...items.values()]
}
