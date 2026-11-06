import type { CorrectionDeliveryLine } from '@/lib/types'

import type { DataEntryRow } from './rows'

/**
 * Makro dla odtwarzacza AutoHotkey (InterClicker, F9 czyta schowek linia po linii, jak „Uzupełnij ITM8”): faktura
 * korygująca w programie sklepu. Dla każdego artykułu numer bonu i dwa Entery, EAN, ilość po korekcie i cena zakupu.
 * Czasy w ms, poprawiamy je tylko tutaj.
 */
export const CORRECTION_MACRO_CONFIG = {
  /** Po każdym numerze bonu program wyszukuje cały dokument; drugi Enter i EAN idą już bez przerwy. */
  afterBon: 1200,
  afterBonSkip: 0,
  afterEan: 0,
  afterQuantity: 100,
  /** Pusty Enter między ilością a ceną. */
  afterSkip: 100,
  /** Po ostatnim Enterze program zapisuje pozycję: przy krótszej przerwie nie nadążał z kolejnym numerem bonu. */
  afterPrice: 1000,
  /** Program sklepu dostaje cenę z taką liczbą miejsc po przecinku. */
  priceDigits: 2,
} as const

/** Pola wiersza tabeli, z których powstaje makro. */
export type CorrectionRow = Pick<
  DataEntryRow,
  'id' | 'ean' | 'eanFallback' | 'itm8' | 'name' | 'quantity' | 'unitNet' | 'before'
>

export type CorrectionMacroItem = {
  key: string
  ean: string
  name: string
  /** Stan bonu; `null`, gdy bonu nie ma w raportach. */
  bonQuantity: number | null
  bonPrice: number | null
  quantity: number
  price: number
  /** Faktura „przed korektą” ma inną ilość niż bon: inna jednostka albo bon już poprawiony. */
  bonDiffers: boolean
}

export type CorrectionMacroProblem<Row extends CorrectionRow = CorrectionRow> = {
  row: Row
  reason: string
  /** Pomoże przypisanie pozycji do artykułu z bonu. */
  canLink: boolean
}

export type CorrectionMacroPlan<Row extends CorrectionRow = CorrectionRow> = {
  items: CorrectionMacroItem[]
  problems: CorrectionMacroProblem<Row>[]
  /** Pozycje, których korekta nie zmienia. */
  unchanged: number
}

const key = (code: string | null | undefined) => (code ?? '').replace(/^0+/, '')

const round = (value: number, digits: number) => Number(value.toFixed(digits))

const same = (a: number, b: number, tolerance = 0.0005) => Math.abs(a - b) < tolerance

/**
 * Linie bonu z jednym ITM8 razem: artykuł bywa na bonie kilka razy, a w programie sklepu to wtedy jedna pozycja do
 * poprawy. Cena = wartość / ilość.
 */
export function mergeBonLines(lines: CorrectionDeliveryLine[]) {
  const merged = new Map<string, CorrectionDeliveryLine>()
  for (const line of lines) {
    const id = key(line.itm8) || key(line.ean)
    const known = merged.get(id)
    if (!known) {
      merged.set(id, line)
      continue
    }
    const quantity = (known.quantity ?? 0) + (line.quantity ?? 0)
    const value = (known.quantity ?? 0) * (known.purchasePrice ?? 0) + (line.quantity ?? 0) * (line.purchasePrice ?? 0)
    merged.set(id, {
      ...known,
      quantity,
      purchasePrice: quantity === 0 ? (line.purchasePrice ?? known.purchasePrice) : value / quantity,
    })
  }
  return [...merged.values()]
}

/** Linia bonu pozycji: po ITM8 albo EAN-ie linii lub jej artykułu z kartoteki (mięso ma w dokumentach lokalny ITM8). */
export function bonLineOf(lines: CorrectionDeliveryLine[], row: CorrectionRow) {
  const itm8 = key(row.itm8)
  const ean = row.eanFallback ? '' : key(row.ean)
  return (
    lines.find(
      (line) =>
        (itm8 !== '' && (key(line.itm8) === itm8 || key(line.articleItm8) === itm8)) ||
        (ean !== '' && (key(line.ean) === ean || key(line.articleEan) === ean)),
    ) ?? null
  )
}

/**
 * Co pozycja bez wierszy „przed korektą” zmienia na bonie. Ilość na minusie (zwrot) albo na plusie z wartością to
 * różnica ilości. Ilość na plusie z ceną na minusie to obniżka ceny (Gniezno: 400 × -0,34). Pozycja z wartością 0 jest
 * bez zmian: dostawcy powtarzają w korekcie artykuły, których nie ruszają (Arpex, Masterbread).
 */
function difference(row: CorrectionRow) {
  const quantity = row.quantity ?? 0
  const price = row.unitNet ?? 0
  if (quantity === 0 || (quantity > 0 && Math.abs(quantity * price) < 0.005)) return null
  return quantity > 0 && price < 0 ? { quantity: 0, price } : { quantity, price: 0 }
}

/** Pozycja z wierszem „przed korektą”, która zmienia ilość albo cenę (wiersze bez zmian też mają taką parę). */
function changedState(row: CorrectionRow) {
  const before = row.before
  if (!before) return false
  return !same(before.quantity ?? 0, row.quantity ?? 0) || !same(before.unitNet ?? 0, row.unitNet ?? 0)
}

/**
 * Plan makra z wierszy tabeli (artykuły, bez kaucji i dostawy). `lines`: linie bonu po wcześniejszych korektach, `null`
 * gdy bonu nie ma w raportach. Korekta z wierszami „przed” (`hasBeforeRows`) podaje stan po korekcie, więc ilość i cenę
 * bierzemy z pozycji. Korekta bez nich podaje różnice: ilość to stan bonu plus pozycja, a cena z bonu, bo jej nie
 * zmienia. Artykułu spoza bonu makro nie wpisuje: program nie znalazłby linii, a kolejne klawisze trafiłyby w złe pola.
 */
export function planCorrectionMacro<Row extends CorrectionRow>(
  rows: Row[],
  hasBeforeRows: boolean,
  lines: CorrectionDeliveryLine[] | null,
): CorrectionMacroPlan<Row> {
  const bon = lines ? mergeBonLines(lines) : null
  const plan: CorrectionMacroPlan<Row> = { items: [], problems: [], unchanged: 0 }
  const groups = new Map<string, { line: CorrectionDeliveryLine | null; rows: Row[] }>()

  for (const row of rows) {
    const changes = hasBeforeRows ? changedState(row) : difference(row) !== null
    if (!hasBeforeRows && !changes) {
      plan.unchanged += 1
      continue
    }
    const line = bon ? bonLineOf(bon, row) : null
    if (!line && (bon || !/^\d+$/.test(row.ean) || row.eanFallback)) {
      if (changes) {
        plan.problems.push({
          row,
          reason: bon ? 'Tego artykułu nie ma na bonie' : 'Pozycja nie ma EAN-u',
          canLink: true,
        })
      } else {
        plan.unchanged += 1
      }
      continue
    }
    if (!line && !hasBeforeRows) {
      plan.problems.push({
        row,
        reason: 'Korekta podaje różnicę, a bonu nie ma w raportach: wybierz bon z listy albo wpisz ręcznie',
        canLink: false,
      })
      continue
    }
    const id = line ? `bon:${key(line.itm8) || key(line.ean)}` : `ean:${key(row.ean)}`
    const group = groups.get(id) ?? { line, rows: [] }
    group.rows.push(row)
    groups.set(id, group)
  }

  for (const [id, { line, rows: grouped }] of groups) {
    const first = grouped[0]
    const ean = first.ean
    const bonQuantity = line?.quantity ?? null
    const bonPrice = line?.purchasePrice ?? null
    if (hasBeforeRows) {
      if (!grouped.some(changedState)) {
        plan.unchanged += grouped.length
        continue
      }
      if (grouped.some((row) => row.quantity === null || row.unitNet === null)) {
        plan.problems.push({ row: first, reason: 'Uzupełnij ilość i cenę w tabeli', canLink: false })
        continue
      }
      const quantity = round(
        sum(grouped, (row) => row.quantity ?? 0),
        3,
      )
      if (quantity < 0) {
        plan.problems.push({ row: first, reason: 'Ilość po korekcie jest na minusie', canLink: false })
        continue
      }
      const value = sum(grouped, (row) => (row.quantity ?? 0) * (row.unitNet ?? 0))
      const before = sum(grouped, (row) => row.before?.quantity ?? row.quantity ?? 0)
      plan.items.push({
        key: id,
        ean,
        name: first.name,
        bonQuantity,
        bonPrice,
        quantity,
        price: round(quantity === 0 ? (grouped[grouped.length - 1].unitNet ?? 0) : value / quantity, 4),
        bonDiffers: bonQuantity !== null && !same(before, bonQuantity),
      })
      continue
    }

    // Tu zawsze jest linia bonu: bez niej pozycja trafiła do problemów.
    const changes = grouped.map(difference)
    const quantity = round((bonQuantity ?? 0) + sum(changes, (change) => change?.quantity ?? 0), 3)
    const price = round((bonPrice ?? Math.abs(first.unitNet ?? 0)) + sum(changes, (change) => change?.price ?? 0), 4)
    if (quantity < 0) {
      plan.problems.push({
        row: first,
        reason: `Korekta zabiera więcej, niż jest na bonie (${formatMacroQuantity(bonQuantity ?? 0)})`,
        canLink: true,
      })
    } else if (price < 0) {
      plan.problems.push({ row: first, reason: 'Cena po korekcie jest na minusie', canLink: false })
    } else {
      plan.items.push({ key: id, ean, name: first.name, bonQuantity, bonPrice, quantity, price, bonDiffers: false })
    }
  }
  return plan
}

function sum<T>(items: T[], value: (item: T) => number) {
  return items.reduce((total, item) => total + value(item), 0)
}

/**
 * Linie bonu do przypięcia pozycji korekty: mięso ma w fakturze własną nazwę i kod dostawcy, więc najpierw linie z tą
 * samą ceną i wspólnymi początkami słów („ĆWIARTKA TYLNA Z KURCZĄT” → „CWIARTKA SWIEZA KL A”). Tylko linie z artykułem
 * z kartoteki, bo przypięcie zapisuje się przy artykule.
 */
export function bonLineSuggestions(lines: CorrectionDeliveryLine[], name: string, price: number | null) {
  const words = (text: string) =>
    text
      .normalize('NFD')
      .replace(/\p{M}/gu, '')
      .replace(/ł/gi, 'l')
      .toUpperCase()
      .split(/[^A-Z0-9]+/)
      .filter((word) => word.length >= 3)
      .map((word) => word.slice(0, 3))
  const wanted = new Set(words(name))
  const score = (line: CorrectionDeliveryLine) =>
    words(line.name ?? '').filter((word) => wanted.has(word)).length +
    (price !== null && line.purchasePrice !== null && same(price, line.purchasePrice, 0.005) ? 2 : 0)
  return lines
    .filter((line) => line.articleItm8 !== null && line.articleEan !== null)
    .map((line) => ({ line, score: score(line) }))
    .sort((a, b) => b.score - a.score)
    .map((item) => item.line)
}

/** Ilość do programu sklepu: do 3 miejsc, z przecinkiem. */
export const formatMacroQuantity = (value: number) => String(round(value, 3)).replace('.', ',')

export const formatMacroPrice = (value: number) => value.toFixed(CORRECTION_MACRO_CONFIG.priceDigits).replace('.', ',')

/** Skrypt makra: numer bonu i kroki osobno dla każdej pozycji; linie z `#` odtwarzacz pomija. CRLF jak w Windows. */
export function buildCorrectionMacro(bonNumber: string, items: CorrectionMacroItem[]) {
  const c = CORRECTION_MACRO_CONFIG
  const out: string[] = []
  // Odtwarzacz sam czeka 50 ms po każdej linii, więc przerwa 0 to brak linii SLEEP.
  const key = (name: string, sleep: number) => {
    out.push(`KEY|${name}`)
    if (sleep > 0) out.push(`SLEEP|${sleep}`)
  }
  const enter = (text: string, sleep: number) => {
    out.push(`TEXT|${text}`)
    key('{Enter}', sleep)
  }

  out.push(`# Korekta bonu ${bonNumber}: ${items.length} poz.`)
  for (const item of items) {
    out.push(`# EAN ${item.ean}`)
    enter(bonNumber, c.afterBon)
    key('{Enter}', c.afterBonSkip)
    enter(item.ean, c.afterEan)
    enter(formatMacroQuantity(item.quantity), c.afterQuantity)
    key('{Enter}', c.afterSkip)
    enter(formatMacroPrice(item.price), c.afterPrice)
  }
  return out.join('\r\n') + '\r\n'
}
