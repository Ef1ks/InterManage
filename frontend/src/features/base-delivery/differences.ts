import type { DataEntryRow } from '@/features/data-entry/rows'
import type { TemporaryNote } from '@/lib/types'

/**
 * Rodzaj różnicy między tabelą dostawy z bazy a tymczasowymi bonami z Mercalys: artykuł z tabeli (z ilością) nie
 * wprowadzony do programu, artykuł w programie spoza tabeli, inna ilość albo inna cena zakupu.
 */
export type DifferenceKind = 'missing' | 'extra' | 'quantity' | 'price'

/**
 * Różnica artykułu (ilości sumowane po liniach) albo, przy cenie, jednej linii tabeli: program ma jedną cenę
 * artykułu, a linie bazy bywają w różnych cenach (CYTRYNA 1/5 po 7,90 i CYTRYNA KL1-15KG po 7,50 pod jednym
 * EAN-em). Ilość w programie {@code null} = nie dotyczy (różnica ceny nie porównuje ilości).
 */
export type Difference = {
  key: string
  kinds: DifferenceKind[]
  itm8: string | null
  ean: string | null
  name: string
  carriers: string[]
  sheetQuantity: number
  programQuantity: number | null
  sheetPrice: number | null
  programPrice: number | null
}

export const DIFFERENCE_LABELS: Record<DifferenceKind, string> = {
  missing: 'Brak w programie',
  extra: 'Tylko w programie',
  quantity: 'Inna ilość',
  price: 'Inna cena',
}

const ORDER: DifferenceKind[] = ['missing', 'extra', 'quantity', 'price']
/** Ilości do 3 miejsc (kg), ceny z programu do grosza, a C_Cesja z bazy bywa dokładniejsza. */
const QUANTITY_TOLERANCE = 0.0005
const PRICE_TOLERANCE = 0.005
/** „Referencja dostawcy” spoza bazy bywa krótkim kodem dostawcy (539), a nie ITM8. */
const MIN_REFERENCE_DIGITS = 5

/** Kod do porównania: same cyfry bez zer wiodących (ITM8 z kartoteki ma 13 cyfr, z bazy 8). */
const digits = (value: string | null | undefined) => (value ?? '').replace(/\D/g, '').replace(/^0+/, '')

/**
 * Towar na wagę: kod z etykiety wagi (2 + cyfra 1-9, 12-13 cyfr, np. IMBIR 5KG 2192850000007 z bazy) i krótki EAN
 * z kartoteki (IMBIR LUZ 0000000219285) to ten sam artykuł, jeśli zgadza się pierwszych 6 cyfr.
 */
function scaleKey(ean: string | null | undefined) {
  const code = digits(ean)
  if (/^2[1-9]\d{10,11}$/.test(code)) return `s:${code.slice(0, 6)}`
  if (/^2[1-9]\d{4}$/.test(code)) return `s:${code}`
  return null
}

/**
 * Klucze, po których łączymy. ITM8 jest jeden: z kartoteki, z pliku bazy i „Kod ITM8” / „Referencja dostawcy”
 * z programu, bo nowy artykuł z bazy dostaje w programie ITM8 z pliku bazy (KAWA DECAF 61625742), a kartoteka
 * sklepu jeszcze go nie ma albo trzyma EAN pod innym ITM8. Do tego EAN-y (w bonie z programu wszystkie EAN-y
 * artykułu z kartoteki: multikody i dawne pary) i kod towaru na wagę.
 */
function identities(itm8s: (string | null | undefined)[], eans: (string | null | undefined)[]) {
  const keys = [...itm8s.map((itm8) => `i:${digits(itm8)}`), ...eans.map((ean) => `e:${digits(ean)}`)].filter(
    (key) => !key.endsWith(':'),
  )
  for (const ean of eans) {
    const scale = scaleKey(ean)
    if (scale) keys.push(scale)
  }
  return [...new Set(keys)]
}

/** Średnia cena ważona ilością (linie z ilością 0 tylko, gdy innych nie ma). */
function averagePrice(items: { quantity: number; price: number | null }[]) {
  const priced = items.filter((item) => item.price !== null)
  const counted = priced.filter((item) => item.quantity !== 0)
  if (counted.length === 0) return priced[0]?.price ?? null
  const quantity = counted.reduce((sum, item) => sum + item.quantity, 0)
  if (quantity === 0) return counted[0].price
  return counted.reduce((sum, item) => sum + item.quantity * (item.price ?? 0), 0) / quantity
}

type Item =
  | { side: 'sheet'; row: DataEntryRow; keys: string[] }
  | { side: 'program'; line: TemporaryNote['lines'][number]; keys: string[] }

/**
 * Różnice między wierszami tabeli a liniami bonów z programu. Wiersze i linie o wspólnym ITM8, EAN-ie albo kodzie
 * wagi to jeden artykuł, także gdy program zsumował pod jednym artykułem kilka linii bazy (AVOKADO SZT 18 + AVOKADO
 * HASS 20 = 38 w programie). Artykuł z ilością 0 w tabeli (nie przyjechał), którego nie ma w programie, nie jest
 * różnicą. Cenę porównujemy dla każdej linii tabeli z ilością (linia, która nie przyjechała, nie ma czego
 * sprawdzać: CYTRYNA 1/5 po 7,90 z ilością 0 obok CYTRYNA KL1-15KG po 7,50) z ceną artykułu w programie (z kilku
 * cen programu z najbliższą). {@code compared} to liczba porównanych artykułów.
 */
export function findDifferences(rows: DataEntryRow[], notes: TemporaryNote[]) {
  const items: Item[] = []
  for (const row of rows) {
    const keys = identities([row.itm8, row.base?.itm8], row.eanFallback ? [] : [row.ean])
    if (keys.length > 0) items.push({ side: 'sheet', row, keys })
  }
  for (const note of notes) {
    for (const line of note.lines) {
      const reference = digits(line.reference).length >= MIN_REFERENCE_DIGITS ? line.reference : null
      const keys = identities([line.itm8, reference], [line.ean, ...line.catalogEans])
      items.push({ side: 'program', line, keys: keys.length > 0 ? keys : [`n:${line.name}`] })
    }
  }

  // Jeden artykuł = wiersze i linie połączone wspólnymi kluczami (union-find).
  const parent = items.map((_, index) => index)
  const find = (index: number): number => {
    let root = index
    while (parent[root] !== root) root = parent[root]
    while (parent[index] !== root) [index, parent[index]] = [parent[index], root]
    return root
  }
  const byKey = new Map<string, number>()
  items.forEach((item, index) => {
    for (const key of item.keys) {
      const other = byKey.get(key)
      if (other === undefined) byKey.set(key, index)
      else parent[find(index)] = find(other)
    }
  })
  const groups = new Map<number, Item[]>()
  items.forEach((item, index) => {
    const root = find(index)
    groups.set(root, [...(groups.get(root) ?? []), item])
  })

  const result: Difference[] = []
  for (const group of groups.values()) {
    const sheet = group.flatMap((item) => (item.side === 'sheet' ? [item.row] : []))
    const program = group.flatMap((item) => (item.side === 'program' ? [item.line] : []))
    const sheetQuantity = sheet.reduce((sum, row) => sum + (row.quantity ?? 0), 0)
    const programQuantity = program.reduce((sum, line) => sum + line.quantity, 0)

    if (Math.abs(sheetQuantity - programQuantity) > QUANTITY_TOLERANCE) {
      const first = sheet[0]
      const kind: DifferenceKind =
        program.length === 0 || programQuantity === 0
          ? 'missing'
          : sheet.length === 0 || sheetQuantity === 0
            ? 'extra'
            : 'quantity'
      result.push({
        key: `q:${group[0].keys[0]}`,
        kinds: [kind],
        itm8: first ? (first.itm8 ?? first.base?.itm8 ?? null) : program[0].itm8,
        ean: first ? first.ean || null : program[0].ean,
        name: first ? first.name : program[0].name,
        carriers: [...new Set(sheet.flatMap((row) => (row.base?.carrier ? [row.base.carrier] : [])))],
        sheetQuantity,
        programQuantity,
        sheetPrice: averagePrice(sheet.map((row) => ({ quantity: row.quantity ?? 0, price: row.unitNet }))),
        programPrice: averagePrice(program.map((line) => ({ quantity: line.quantity, price: line.purchasePrice }))),
      })
    }

    const programPrices = program.flatMap((line) => (line.purchasePrice !== null ? [line.purchasePrice] : []))
    if (programPrices.length === 0) continue
    for (const row of sheet) {
      const price = row.unitNet
      if (price === null || !row.quantity) continue
      const closest = programPrices.reduce((best, candidate) =>
        Math.abs(candidate - price) < Math.abs(best - price) ? candidate : best,
      )
      if (Math.abs(closest - price) <= PRICE_TOLERANCE) continue
      result.push({
        key: `p:${row.id}`,
        kinds: ['price'],
        itm8: row.base?.itm8 ?? row.itm8,
        ean: row.ean || null,
        name: row.name,
        carriers: row.base?.carrier ? [row.base.carrier] : [],
        sheetQuantity: row.quantity ?? 0,
        programQuantity: null,
        sheetPrice: price,
        programPrice: closest,
      })
    }
  }
  const rank = (difference: Difference) => ORDER.indexOf(difference.kinds[0])
  result.sort((a, b) => rank(a) - rank(b) || a.name.localeCompare(b.name, 'pl'))
  return { differences: result, compared: groups.size }
}
