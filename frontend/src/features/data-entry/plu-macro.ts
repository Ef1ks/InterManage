import type { DataEntryRow } from './rows'

/** `inCatalog`: artykuł z kartoteki dodany z zaznaczenia (zwykle ma już kod PLU). */
export type PluItem = { ean: string; name: string; inCatalog: boolean }

type PluRow = Pick<DataEntryRow, 'ean' | 'eanFallback' | 'name' | 'known' | 'itm8'>

const SCALE_24 = /^24\d{10,11}$/

/**
 * Kody 24 (pełny EAN: 13 cyfr albo 12 bez cyfry kontrolnej), każdy raz. Z całej tabeli tylko nowe artykuły, bo
 * z kartoteki mają już kod PLU; szkic z pliku CADBEZP (znany, ale bez ITM8) to dalej nowy. Do tego każdy zaznaczony
 * wiersz z kodem 24, także artykuł z kartoteki i bez zapisu pliku. Kody zastępcze z faktury się nie liczą.
 */
export function pluItems(rows: PluRow[], selected: PluRow[] = []): PluItem[] {
  const items = new Map<string, PluItem>()
  const add = (row: PluRow, inCatalog: boolean) => {
    if (row.eanFallback || !SCALE_24.test(row.ean) || items.has(row.ean)) return
    items.set(row.ean, { ean: row.ean, name: row.name, inCatalog })
  }
  for (const row of rows) {
    if (!(row.known && row.itm8 !== null)) add(row, false)
  }
  for (const row of selected) add(row, row.known && row.itm8 !== null)
  return [...items.values()]
}
