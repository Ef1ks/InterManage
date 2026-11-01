import { type DataEntryRow, serviceKind } from './rows'

/**
 * Ten sam artykuł kilka razy na fakturze (ten sam EAN, np. w dwóch cenach). Wiersze z jednym EAN-em to jeden artykuł:
 * zmiana w jednym przechodzi na pozostałe, a pasek nad tabelą uśrednia ceny albo łączy je w jeden wiersz.
 */

/** Pola artykułu wspólne dla wierszy z tym samym EAN-em; ilość jest osobna dla każdej pozycji faktury. */
const SHARED_FIELDS = [
  'unitNet',
  'name',
  'brand',
  'vatPercent',
  'salePrice',
  'subfamilyCode',
  'nomenclaturePrefix',
  'unit',
  'netContent',
  'nameFromInvoice',
  'edited',
  'suggestion',
] as const satisfies readonly (keyof DataEntryRow)[]

/** Klucz artykułu: EAN bez zer wiodących; kaucje, dostawa i wiersze bez EAN-u nie mają pary. */
export function articleKey(row: DataEntryRow) {
  if (row.ean === '' || serviceKind(row) !== null) return null
  return row.ean.replace(/^0+/, '') || null
}

/** Grupy wierszy z tym samym EAN-em (co najmniej dwa), w kolejności tabeli. */
export function duplicateGroups(rows: DataEntryRow[]) {
  const groups = new Map<string, DataEntryRow[]>()
  for (const row of rows) {
    const key = articleKey(row)
    if (key) groups.set(key, [...(groups.get(key) ?? []), row])
  }
  return [...groups.values()].filter((group) => group.length > 1)
}

/** Grupy, w których ten sam EAN ma różne ceny zakupu. */
export function priceConflicts(rows: DataEntryRow[]) {
  return duplicateGroups(rows).filter((group) => new Set(group.map((row) => row.unitNet)).size > 1)
}

/** Cena średnia ważona ilością (wartość netto / ilość); bez ilości zwykła średnia cen. */
function averagePrice(group: DataEntryRow[]) {
  const priced = group.filter((row) => row.unitNet !== null)
  if (priced.length === 0) return null
  const quantity = priced.reduce((sum, row) => sum + (row.quantity ?? 0), 0)
  const allCounted = priced.every((row) => row.quantity !== null && row.quantity > 0)
  const average =
    allCounted && quantity > 0
      ? priced.reduce((sum, row) => sum + (row.quantity ?? 0) * (row.unitNet ?? 0), 0) / quantity
      : priced.reduce((sum, row) => sum + (row.unitNet ?? 0), 0) / priced.length
  return Math.round(average * 10000) / 10000
}

/** Ta sama, średnia cena zakupu we wszystkich wierszach z tym samym EAN-em. */
export function withAveragedPrices(rows: DataEntryRow[]) {
  const prices = new Map<string, number | null>()
  for (const group of priceConflicts(rows)) {
    const price = averagePrice(group)
    for (const row of group) prices.set(row.id, price)
  }
  return rows.map((row) => (prices.has(row.id) ? { ...row, unitNet: prices.get(row.id) ?? null } : row))
}

/** Wiersze z tym samym EAN-em złączone w pierwszy z nich: ilości zsumowane, cena średnia. */
export function withMergedDuplicates(rows: DataEntryRow[]) {
  const merged = new Map<string, DataEntryRow>()
  const removed = new Set<string>()
  for (const group of duplicateGroups(rows)) {
    const [first, ...rest] = group
    const counted = group.filter((row) => row.quantity !== null)
    const quantity = counted.length === 0 ? null : Math.round(counted.reduce((sum, row) => sum + (row.quantity ?? 0), 0) * 1000) / 1000
    merged.set(first.id, { ...first, quantity, unitNet: averagePrice(group) })
    for (const row of rest) removed.add(row.id)
  }
  return rows.filter((row) => !removed.has(row.id)).map((row) => merged.get(row.id) ?? row)
}

/**
 * Zmiana z tabeli przeniesiona na pozostałe wiersze z tym samym EAN-em: pola artykułu zmienione w wierszu (cena
 * zakupu, nazwa, nomenklatura, rodzaj, waga...) dostają też jego pary. Wiersz, któremu zmieniono EAN, nie przenosi
 * zmian; wiersze zmienione w tym samym kroku (np. wklejenie w kilka) zostają takie, jak je zmieniono.
 */
export function withSyncedDuplicates(before: DataEntryRow[], after: DataEntryRow[]) {
  const previous = new Map(before.map((row) => [row.id, row]))
  const patches = new Map<string, Partial<DataEntryRow>>()
  const changed = new Set<string>()
  for (const row of after) {
    const old = previous.get(row.id)
    const key = articleKey(row)
    if (!old || old === row || !key || articleKey(old) !== key) continue
    const patch: Partial<DataEntryRow> = {}
    for (const field of SHARED_FIELDS) {
      if (row[field] !== old[field]) Object.assign(patch, { [field]: row[field] })
    }
    if (Object.keys(patch).length === 0) continue
    changed.add(row.id)
    patches.set(key, { ...patches.get(key), ...patch })
  }
  if (patches.size === 0) return after
  return after.map((row) => {
    if (changed.has(row.id)) return row
    const key = articleKey(row)
    const patch = key ? patches.get(key) : undefined
    if (!patch) return row
    return { ...row, ...patch, edited: patch.edited ? { ...row.edited, ...patch.edited } : row.edited }
  })
}
