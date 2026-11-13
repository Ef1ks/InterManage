import type { CorrectionDeliveryLine } from '@/lib/types'

import {
  correctionArticleLines,
  correctionBonLine,
  correctionFinishLines,
  correctionStartLines,
} from './mercalys-macro'
import type { DataEntryRow } from './rows'

/**
 * Makro dla odtwarzacza AutoHotkey (InterClicker, Alt+E czyta schowek linia po linii): faktura korygująca w programie
 * sklepu od otwarcia Mercalys do zamknięcia PDF-a. Dla każdego artykułu numer bonu i dwa Entery, EAN, ilość po korekcie
 * i cena zakupu. Czasy w ms, poprawiamy je tylko tutaj.
 */
export const CORRECTION_MACRO_CONFIG = {
  /** Po numerze bonu i pierwszym Enterze program wyszukuje cały dokument; drugi Enter dopiero po tej przerwie. */
  afterBon: 800,
  /** Po drugim Enterze, przed EAN-em. */
  afterBonSkip: 100,
  /**
   * Najdłużej tyle makro czeka, aż Mercalys wczyta artykuł po EAN-ie (cena z bonu w polu), wyczyści wiersz po bonie
   * i przyjmie ilość i cenę. Dłużej = makro staje z komunikatem, zamiast pisać w złe pola.
   */
  load: 20000,
  /** Artykuł spoza bonu nie ma na co czekać: zwykła przerwa po EAN-ie. */
  afterEan: 800,
  /** Krócej niż 300 ms program nie nadąża między ilością a ceną. */
  afterQuantity: 300,
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

/** Bon korekty i jego linie po wcześniejszych korektach; `lines` = `null`, gdy bonu nie ma w raportach. */
export type CorrectionBon = { number: string; lines: CorrectionDeliveryLine[] | null }

export type CorrectionMacroItem = {
  key: string
  /** Bon, na którym jest artykuł: makro wpisuje go w Nr BL przy tej pozycji (korekta bazy dotyczy kilku WZ). */
  bonNumber: string
  ean: string
  name: string
  /** Stan bonu; `null`, gdy bonu nie ma w raportach. */
  bonQuantity: number | null
  bonPrice: number | null
  quantity: number
  price: number
  /** Faktura „przed korektą” ma inną ilość niż bon: inna jednostka albo bon już poprawiony. */
  bonDiffers: boolean
  /** Korekta dopisuje artykuł, którego nie ma na bonie (zamiana artykułu, np. sałata masłowa na lodową). */
  added: boolean
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
 * Plan makra z wierszy tabeli (artykuły, bez kaucji i dostawy). `bons`: bony korekty, pierwszy główny; artykuł idzie na
 * bon, na którym jest (faktura bazy obejmuje kilka WZ, więc jedna korekta poprawia kilka bonów w jednym protokole), a bez
 * trafienia na główny. Linie `null` = bonu nie ma w raportach. Korekta z wierszami „przed” (`hasBeforeRows`) podaje stan po korekcie, więc ilość i cenę
 * bierzemy z pozycji. Korekta bez nich podaje różnice: ilość to stan bonu plus pozycja, a cena z bonu, bo jej nie
 * zmienia. Artykułu spoza bonu makro nie wpisuje: program nie znalazłby linii, a kolejne klawisze trafiłyby w złe pola.
 * Wyjątkiem jest zamiana artykułu (`before.quantity` = 0): korekta sama mówi, że dochodzi do dostawy.
 */
export function planCorrectionMacro<Row extends CorrectionRow>(
  rows: Row[],
  hasBeforeRows: boolean,
  bons: CorrectionBon[],
): CorrectionMacroPlan<Row> {
  const merged = bons.map((item) => ({ number: item.number, lines: item.lines ? mergeBonLines(item.lines) : null }))
  const main = merged[0]
  // Linie znane choć dla jednego bonu: wtedy artykuł spoza wszystkich bonów to problem, a nie bon spoza raportów.
  const bon = merged.some((item) => item.lines !== null) ? merged : null
  const plan: CorrectionMacroPlan<Row> = { items: [], problems: [], unchanged: 0 }
  const groups = new Map<string, { line: CorrectionDeliveryLine | null; bonNumber: string; rows: Row[] }>()

  for (const row of rows) {
    const changes = hasBeforeRows ? changedState(row) : difference(row) !== null
    if (!hasBeforeRows && !changes) {
      plan.unchanged += 1
      continue
    }
    let line: CorrectionDeliveryLine | null = null
    let bonNumber = main?.number ?? ''
    for (const item of merged) {
      const found = item.lines ? bonLineOf(item.lines, row) : null
      if (found) {
        line = found
        bonNumber = item.number
        break
      }
    }
    const added = row.before?.quantity === 0 && /^\d+$/.test(row.ean) && !row.eanFallback
    if (!line && !added && (bon || !/^\d+$/.test(row.ean) || row.eanFallback)) {
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
    const id = `${bonNumber}:${line ? `bon:${key(line.itm8) || key(line.ean)}` : `ean:${key(row.ean)}`}`
    const group = groups.get(id) ?? { line, bonNumber, rows: [] }
    group.rows.push(row)
    groups.set(id, group)
  }

  for (const [id, { line, bonNumber, rows: grouped }] of groups) {
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
        bonNumber,
        ean,
        name: first.name,
        bonQuantity,
        bonPrice,
        quantity,
        price: round(quantity === 0 ? (grouped[grouped.length - 1].unitNet ?? 0) : value / quantity, 4),
        bonDiffers: bonQuantity !== null && !same(before, bonQuantity),
        added: bon !== null && line === null,
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
      plan.items.push({
        key: id,
        bonNumber,
        ean,
        name: first.name,
        bonQuantity,
        bonPrice,
        quantity,
        price,
        bonDiffers: false,
        added: false,
      })
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

/** NIP bazy SCA PR (dostawca 538): jej korekty idą protokołem typu 10, a nie 8. */
const BASE_SELLER_NIP = '7821977018'

export const isBaseSeller = (nip: string | null | undefined) => nip?.replace(/\D/g, '') === BASE_SELLER_NIP

/**
 * Skrypt makra: nowy protokół, numer bonu pozycji i kroki osobno dla każdej pozycji (pozycje różnych bonów w jednym
 * protokole), po potwierdzeniu pracownika zakończenie; linie z `#` odtwarzacz pomija. `today`: termin płatności,
 * RRRR-MM-DD. CRLF jak w Windows.
 */
export function buildCorrectionMacro(items: CorrectionMacroItem[], today: string, fromBase = false) {
  const bonNumbers = [...new Set(items.map((item) => item.bonNumber))].join(', ')
  const c = CORRECTION_MACRO_CONFIG
  const out: string[] = []
  // Odtwarzacz sam czeka 50 ms po każdej linii, więc przerwa 0 to brak linii SLEEP.
  const key = (name: string, sleep: number) => {
    out.push(`KEY|${name}`)
    if (sleep > 0) out.push(`SLEEP|${sleep}`)
  }

  out.push(`# Korekta bonu ${bonNumbers}: ${items.length} poz.`, ...correctionStartLines(today, fromBase))
  for (const item of items) {
    out.push(`# EAN ${item.ean}, bon ${item.bonNumber}`, correctionBonLine(item.bonNumber), `SLEEP|${c.afterBon}`)
    key('{Enter}', c.afterBonSkip)
    out.push(
      ...correctionArticleLines(
        {
          ean: item.ean,
          quantity: round(item.quantity, 3),
          quantityText: formatMacroQuantity(item.quantity),
          priceText: formatMacroPrice(item.price),
          // Bon spoza raportów (cena nieznana) też ma linię w Mercalys, więc czekamy; artykuł dopisany nie ma.
          waitForPrice: !item.added && item.bonPrice !== 0,
        },
        { load: c.load, afterEan: c.afterEan, afterQuantity: c.afterQuantity },
      ),
      `SLEEP|${c.afterPrice}`,
    )
  }
  out.push(...correctionFinishLines(bonNumbers, items.length))
  return out.join('\r\n') + '\r\n'
}
