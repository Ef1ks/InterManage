import type { DataEntryRow } from './rows'

/** Najwięcej, ile mieści pole ilości w pliku COMMANDE: 2 cyfry. */
export const MAX_QUANTITY = 99

/** Kod, który plik COMMANDE zapisze jako EAN-13 (GTIN-14 z zerem na początku i krótki kod wewnętrzny też). */
export const ORDER_EAN = /^(0\d{13}|\d{8,13}|\d{1,6})$/

/** Kod z etykiety wagi (2 + cyfra 1-9, 12-13 cyfr): towar ważony w sklepie, nie zamawia się go. */
const SCALE_CODE = /^0*2[1-9]\d{10,11}$/

export type OrderLine = { ean: string; quantity: number }

/**
 * Pozycje pliku zamówienia. Bez ilości, bez prawdziwego EAN-u i z kodem z etykiety wagi wiersz nie jest
 * zamawiany ({@code skipped}), a ten sam
 * EAN w kilku wierszach sumuje się do jednej linii. Ilość, której plik nie zapisze (ułamek, minus, ponad 99), trafia do
 * {@code invalid} i blokuje zapis: zamówienie bez cicho pominiętej pozycji byłoby gorsze niż brak pliku.
 */
export function orderLines(rows: DataEntryRow[]) {
  const byEan = new Map<string, OrderLine>()
  let skipped = 0
  for (const row of rows) {
    if (row.eanFallback || !ORDER_EAN.test(row.ean) || SCALE_CODE.test(row.ean) || !row.quantity) {
      skipped += 1
      continue
    }
    const key = row.ean.replace(/^0+/, '')
    const known = byEan.get(key)
    if (known) known.quantity += row.quantity
    else byEan.set(key, { ean: row.ean, quantity: row.quantity })
  }
  const all = [...byEan.values()]
  const valid = (line: OrderLine) =>
    Number.isInteger(line.quantity) && line.quantity >= 1 && line.quantity <= MAX_QUANTITY
  return { lines: all.filter(valid), invalid: all.filter((line) => !valid(line)), skipped }
}
