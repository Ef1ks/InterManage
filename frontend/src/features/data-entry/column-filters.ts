import { cellText, type Column, type ColumnKey } from './grid-values'
import { plain, type DataEntryRow, type Pricing } from './rows'

/** Pusta wartość w filtrze kolumny. */
export const EMPTY_VALUE = '(puste)'

/** Wartość kolumny z liczbą wierszy (lista w filtrze kolumny). */
export type FilterValue = { value: string; count: number }

/**
 * Warunek filtra kolumny jak „Filtry tekstu” i „Filtry liczb” w Excelu: tekst zawiera wszystkie wpisane słowa (bez
 * wielkości liter i polskich znaków, `*` = dowolny ciąg, `?` = jeden znak) albo liczba w przedziale od-do (włącznie,
 * jedna granica wystarczy).
 */
export type ColumnCondition = { contains: string } | { from: number | null; to: number | null }

/** Filtr jednej kolumny: wybrane wartości z listy i (albo) warunek; oba muszą być spełnione. */
export type ColumnRule = { values?: string[]; condition?: ColumnCondition }

export type ColumnFilters = Partial<Record<ColumnKey, ColumnRule>>

export type SortDirection = 'asc' | 'desc'
export type ColumnSort = { key: ColumnKey; direction: SortDirection }

export const isNumericColumn = (column: Column) => column.align === 'right'

const toNumber = (text: string) => (text === '' ? null : Number(text.replace(',', '.')))

/** Słowa warunku „zawiera” jako wyrażenia (z `*` i `?` jak w Excelu). */
function containsPatterns(text: string) {
  return plain(text)
    .trim()
    .split(/\s+/)
    .filter(Boolean)
    .map(
      (word) =>
        new RegExp(
          word
            .replace(/[.+^${}()|[\]\\]/g, '\\$&')
            .replace(/\*/g, '.*')
            .replace(/\?/g, '.'),
        ),
    )
}

function matchesCondition(text: string, condition: ColumnCondition) {
  if ('contains' in condition) {
    const value = plain(text)
    return containsPatterns(condition.contains).every((pattern) => pattern.test(value))
  }
  const value = toNumber(text)
  if (value === null || Number.isNaN(value)) return false
  // Kwoty w tabeli mają do 4 miejsc, więc granice porównujemy z zapasem na zaokrąglenia.
  if (condition.from !== null && value < condition.from - 1e-9) return false
  if (condition.to !== null && value > condition.to + 1e-9) return false
  return true
}

/** Czy wiersz spełnia filtry wszystkich kolumn. */
export function matchesColumns(row: DataEntryRow, filters: ColumnFilters, pricing: Pricing) {
  for (const [key, rule] of Object.entries(filters) as [ColumnKey, ColumnRule][]) {
    const text = cellText(row, key, pricing)
    if (rule.values && !rule.values.includes(text || EMPTY_VALUE)) return false
    if (rule.condition && !matchesCondition(text, rule.condition)) return false
  }
  return true
}

/** Czy warunek coś zawęża (pusty tekst albo przedział bez granic to brak warunku). */
export function hasCondition(condition: ColumnCondition | undefined) {
  if (!condition) return false
  if ('contains' in condition) return condition.contains.trim() !== ''
  return condition.from !== null || condition.to !== null
}

/** Opis warunku do podpowiedzi przycisku filtra. */
export function conditionLabel(condition: ColumnCondition) {
  if ('contains' in condition) return `zawiera „${condition.contains.trim()}”`
  const number = (value: number) => value.toLocaleString('pl-PL', { maximumFractionDigits: 4 })
  if (condition.from !== null && condition.to !== null)
    return condition.from === condition.to
      ? `= ${number(condition.from)}`
      : `${number(condition.from)}–${number(condition.to)}`
  return condition.from !== null ? `od ${number(condition.from)}` : `do ${number(condition.to ?? 0)}`
}

/** Wartości kolumny z liczbą wierszy, w kolejności listy filtra (liczby rosnąco, tekst alfabetycznie, puste na końcu). */
export function columnValues(rows: DataEntryRow[], column: Column, pricing: Pricing): FilterValue[] {
  const counts = new Map<string, number>()
  for (const row of rows) {
    if (isBlank(row)) continue
    const value = cellText(row, column.key, pricing) || EMPTY_VALUE
    counts.set(value, (counts.get(value) ?? 0) + 1)
  }
  const numeric = isNumericColumn(column)
  return [...counts.entries()]
    .map(([value, count]) => ({ value, count }))
    .sort((a, b) =>
      a.value === EMPTY_VALUE
        ? 1
        : b.value === EMPTY_VALUE
          ? -1
          : numeric
            ? Number(a.value.replace(',', '.')) - Number(b.value.replace(',', '.'))
            : a.value.localeCompare(b.value, 'pl'),
    )
}

/** Pusty wiersz na kolejny EAN: nie bierze udziału w filtrach i sortowaniu, zostaje na końcu. */
const isBlank = (row: DataEntryRow) => row.ean === '' && row.name === ''

/**
 * Wiersze ułożone według kolumny (jak sortowanie w Excelu): puste komórki zawsze na końcu, puste wiersze za nimi.
 * Kolejność wierszy o tej samej wartości się nie zmienia.
 */
export function sortedRows(
  rows: DataEntryRow[],
  key: ColumnKey,
  direction: SortDirection,
  numeric: boolean,
  pricing: Pricing,
): DataEntryRow[] {
  const sign = direction === 'asc' ? 1 : -1
  const filled = rows.filter((row) => !isBlank(row))
  // Tekst komórki liczony raz na wiersz, nie przy każdym porównaniu.
  const texts = new Map(filled.map((row) => [row.id, cellText(row, key, pricing)]))
  const ordered = [...filled].sort((a, b) => {
    const left = texts.get(a.id) ?? ''
    const right = texts.get(b.id) ?? ''
    if (left === '' || right === '') return left === right ? 0 : left === '' ? 1 : -1
    const order = numeric
      ? Number(left.replace(',', '.')) - Number(right.replace(',', '.'))
      : left.localeCompare(right, 'pl', { numeric: true })
    return order * sign
  })
  return [...ordered, ...rows.filter(isBlank)]
}
