import type { DataEntryRow } from '@/features/data-entry/rows'
import type { TemporaryNote } from '@/lib/types'

/**
 * Rodzaj różnicy między tabelą dostawy z bazy a tymczasowymi bonami z Mercalys: artykuł z tabeli (z ilością) nie
 * wprowadzony do programu, artykuł w programie spoza tabeli, inna ilość albo inna cena zakupu.
 */
export type DifferenceKind = 'missing' | 'extra' | 'quantity' | 'price'

export type Difference = {
  key: string
  kinds: DifferenceKind[]
  itm8: string | null
  ean: string | null
  name: string
  carriers: string[]
  sheetQuantity: number
  programQuantity: number
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
 * Klucze, po których łączymy: ITM8 z kartoteki, ITM8 z pliku bazy (w Mercalys „Referencja dostawcy”), EAN-y (w bonie
 * z programu także wszystkie EAN-y artykułu z kartoteki: multikody i dawne pary) i kod towaru na wagę.
 */
function identities(itm8: string | null | undefined, baseItm8: string | null | undefined, eans: (string | null)[]) {
  const keys = [`i:${digits(itm8)}`, `b:${digits(baseItm8)}`, ...eans.map((ean) => `e:${digits(ean)}`)].filter(
    (key) => !key.endsWith(':'),
  )
  for (const ean of eans) {
    const scale = scaleKey(ean)
    if (scale) keys.push(scale)
  }
  return [...new Set(keys)]
}

type Group = {
  key: string
  itm8: string | null
  ean: string | null
  name: string
  carriers: Set<string>
  sheetQuantity: number
  programQuantity: number
  sheetPrice: number | null
  programPrice: number | null
  inSheet: boolean
  inProgram: boolean
}

/**
 * Różnice między wierszami tabeli a liniami bonów z programu. Ten sam artykuł z kilku nośników (i kilku linii bonu)
 * sumujemy. Artykuł z ilością 0 w tabeli (nie przyjechał), którego nie ma w programie, nie jest różnicą.
 * {@code compared} to liczba porównanych artykułów.
 */
export function findDifferences(rows: DataEntryRow[], notes: TemporaryNote[]) {
  const groups: Group[] = []
  const byIdentity = new Map<string, Group>()

  for (const row of rows) {
    const keys = identities(row.itm8, row.base?.itm8, row.eanFallback ? [] : [row.ean])
    if (keys.length === 0) continue
    let group = keys.map((key) => byIdentity.get(key)).find((found) => found !== undefined)
    if (!group) {
      group = {
        key: keys[0],
        itm8: row.itm8,
        ean: row.ean || null,
        name: row.name,
        carriers: new Set(),
        sheetQuantity: 0,
        programQuantity: 0,
        sheetPrice: null,
        programPrice: null,
        inSheet: true,
        inProgram: false,
      }
      groups.push(group)
    }
    for (const key of keys) if (!byIdentity.has(key)) byIdentity.set(key, group)
    group.sheetQuantity += row.quantity ?? 0
    group.sheetPrice ??= row.unitNet
    if (row.base?.carrier) group.carriers.add(row.base.carrier)
  }

  for (const note of notes) {
    for (const line of note.lines) {
      const keys = identities(line.itm8, line.reference, [line.ean, ...line.catalogEans])
      let group = keys.map((key) => byIdentity.get(key)).find((found) => found !== undefined)
      if (!group) {
        group = {
          key: keys[0] ?? `n:${line.name}`,
          itm8: line.itm8,
          ean: line.ean,
          name: line.name,
          carriers: new Set(),
          sheetQuantity: 0,
          programQuantity: 0,
          sheetPrice: null,
          programPrice: null,
          inSheet: false,
          inProgram: true,
        }
        groups.push(group)
        for (const key of keys) byIdentity.set(key, group)
      }
      group.inProgram = true
      group.programQuantity += line.quantity
      group.programPrice ??= line.purchasePrice
    }
  }

  const result: Difference[] = []
  for (const group of groups) {
    const kinds: DifferenceKind[] = []
    const quantityDiffers = Math.abs(group.sheetQuantity - group.programQuantity) > QUANTITY_TOLERANCE
    if (quantityDiffers) {
      if (!group.inProgram || group.programQuantity === 0) kinds.push('missing')
      else if (!group.inSheet || group.sheetQuantity === 0) kinds.push('extra')
      else kinds.push('quantity')
    }
    if (
      group.inSheet &&
      group.inProgram &&
      group.sheetPrice !== null &&
      group.programPrice !== null &&
      Math.abs(group.sheetPrice - group.programPrice) > PRICE_TOLERANCE
    ) {
      kinds.push('price')
    }
    if (kinds.length === 0) continue
    result.push({
      key: group.key,
      kinds,
      itm8: group.itm8,
      ean: group.ean,
      name: group.name,
      carriers: [...group.carriers],
      sheetQuantity: group.sheetQuantity,
      programQuantity: group.programQuantity,
      sheetPrice: group.sheetPrice,
      programPrice: group.programPrice,
    })
  }
  const rank = (difference: Difference) => ORDER.indexOf(difference.kinds[0])
  result.sort((a, b) => rank(a) - rank(b) || a.name.localeCompare(b.name, 'pl'))
  return { differences: result, compared: groups.length }
}
