import type { ProLine } from '@/features/leaflet/leaflet-rows'

import type { DataEntryRow } from './rows'

/**
 * Pozycje pliku cen promocji .PRO z tabeli: EAN i cena sprzedaży brutto. Ten sam EAN idzie raz, z pierwszą ceną
 * ({@code conflicts}: nazwy artykułów z różnymi cenami). {@code sameAsCatalog}: artykuły z ceną równą cenie z kartoteki,
 * czyli być może bez wpisanej ceny promocji.
 */
export function proLines(rows: DataEntryRow[]) {
  const byEan = new Map<string, ProLine>()
  const conflicts = new Set<string>()
  const withoutPrice: string[] = []
  let withoutEan = 0
  let sameAsCatalog = 0
  for (const row of rows) {
    if (row.eanFallback || !/^(0\d{13}|\d{8,13})$/.test(row.ean)) {
      withoutEan += 1
      continue
    }
    const name = row.name || row.ean
    if (!row.salePrice || row.salePrice <= 0) {
      withoutPrice.push(name)
      continue
    }
    const key = row.ean.replace(/^0+/, '')
    const known = byEan.get(key)
    if (known) {
      if (Math.abs(known.price - row.salePrice) >= 0.005) conflicts.add(known.name)
      continue
    }
    byEan.set(key, { ean: row.ean.slice(-13), price: row.salePrice, name })
    if (row.catalog?.salePrice === row.salePrice) sameAsCatalog += 1
  }
  return { lines: [...byEan.values()], withoutEan, withoutPrice, conflicts: [...conflicts], sameAsCatalog }
}
