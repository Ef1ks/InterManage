import { plain } from '@/features/data-entry/rows'
import type { SavedFolderSpec } from '@/lib/saved-folders'
import type { LeafletLine } from '@/lib/types'

/** Folder, z którego program sklepu wczytuje pliki cen promocji .PRO. */
export const PRO_FOLDER: SavedFolderSpec = {
  key: 'leaflet-pro',
  label: 'Pliki cen promocji .PRO',
  suggestedPath: 'N:\\ALOA\\PRO\\IMPORT',
}

/** Oznaczenie pozycji gazetki bez kodu MEA (pozycja do opracowania, nie kod do wpisania). */
export const NO_MEA = 'DDD'

/** Pozycja do opracowania w sklepie: bez kodu MEA (puste albo DDD), cenę promocji wprowadzamy plikiem .PRO. */
export const toProcess = (line: LeafletLine) => line.meaCode === null || line.meaCode === NO_MEA

/** Cena gazetkowa jako rabat od ceny z kartoteki (-0,2 = -20%). */
export const isDiscount = (line: LeafletLine) => line.price !== null && line.price < 0

/**
 * Cena promocji brutto: cena z gazetki, a przy rabacie (od -1 do 0) cena z kartoteki pomniejszona o rabat, do
 * grosza; {@code null} bez ceny albo przy rabacie bez ceny z kartoteki.
 */
export function promoPrice(line: LeafletLine): number | null {
  if (line.price === null) return null
  if (!isDiscount(line)) return line.price
  if (line.regularPrice === null) return null
  return Math.round(line.regularPrice * (1 + line.price) * 100) / 100
}

/** EAN artykułu z kartoteki (z Mercalys), który trafia do plików; {@code null} bez artykułu. */
export const productEan = (line: LeafletLine) => line.product?.catalogEan ?? null

/** Cena promocji inna niż cena sprzedaży z kartoteki: na cenówce jest cena skreślona. */
export function hasStrikePrice(line: LeafletLine) {
  const price = promoPrice(line)
  return price !== null && line.regularPrice !== null && Math.abs(price - line.regularPrice) >= 0.005
}

export type LabelFormat = 'PRO_GAZ_FOR_30' | 'PRO_PRO_FOR_67' | 'NAJTANSZE' | 'BIAL_FOR_67'

/** Formaty cenówek w programie sklepu, w kolejności z wyboru; nazwa trafia do nazwy pliku etykiet. */
export const LABEL_FORMATS: { format: LabelFormat; label: string; description: string }[] = [
  {
    format: 'PRO_GAZ_FOR_30',
    label: 'Produkt z gazetki',
    description: 'Karteczka „Produkt z gazetki”, gdy cena z gazetki jest taka jak w kartotece',
  },
  {
    format: 'PRO_PRO_FOR_67',
    label: 'Promo / nadstawka',
    description: 'Karteczka i nadstawka PROMO oraz produkt z gazetki z ceną skreśloną',
  },
  { format: 'NAJTANSZE', label: 'Najtańszy', description: 'Karteczka „Najtańszy”' },
  { format: 'BIAL_FOR_67', label: 'Biała etykieta', description: 'Biała etykieta cenowa' },
]

/** Rodzaj cenówki z kolumny OZNACZENIE PRZY PÓŁCE; {@code null} = cenówka nie z Mercalys (np. z generatora). */
export type ShelfKind = 'leaflet' | 'promo' | 'cheapest' | 'white' | 'extra'

export function shelfKind(shelfLabel: string | null): ShelfKind | null {
  if (!shelfLabel) return null
  const text = plain(shelfLabel).replace(/\s+/g, ' ').trim()
  if (text.includes('EXTRA ZNACZEK')) return 'extra'
  if (text.includes('NAJTANSZY')) return 'cheapest'
  if (text.includes('BIALA ETYKIETA')) return 'white'
  if (text === 'KARTECZKA PRODUKT Z GAZETKI Z MERKALYS' || text.startsWith('PRODUKT Z GAZETKI LUB')) return 'leaflet'
  if (text === 'NADSTAWKA PROMO' || text === 'NADSTAWKA PRODUKT Z GAZETKI' || text === 'KARTECZKA PROMO Z MERKALYS')
    return 'promo'
  return null
}

/**
 * Format cenówki pozycji: „Produkt z gazetki” idzie do PRO_GAZ_FOR_30, ale gdy cena promocji jest inna niż cena
 * sprzedaży z kartoteki (cena skreślona), do PRO_PRO_FOR_67 razem z karteczkami i nadstawkami PROMO.
 */
export function labelFormat(line: LeafletLine): LabelFormat | null {
  switch (shelfKind(line.shelfLabel)) {
    case 'leaflet':
      return hasStrikePrice(line) ? 'PRO_PRO_FOR_67' : 'PRO_GAZ_FOR_30'
    case 'promo':
      return 'PRO_PRO_FOR_67'
    case 'cheapest':
      return 'NAJTANSZE'
    case 'white':
      return 'BIAL_FOR_67'
    default:
      return null
  }
}

/**
 * EAN-y artykułów z kartoteki do pliku etykiet formatu: każdy artykuł raz, choć gazetka ma go pod kilkoma EAN-ami.
 * Pozycje bez artykułu są pominięte ({@code withoutProduct}).
 */
export function labelEans(lines: LeafletLine[], format: LabelFormat) {
  const eans = new Set<string>()
  let withoutProduct = 0
  for (const line of lines) {
    if (labelFormat(line) !== format) continue
    const ean = productEan(line)
    if (ean) eans.add(ean)
    else withoutProduct++
  }
  return { eans: [...eans], withoutProduct }
}

/** Artykuły do karteczek „Extra Znaczek”, każdy artykuł raz (bez artykułu z kartoteki po EAN-ie z gazetki). */
export function extraMarks(lines: LeafletLine[]) {
  const seen = new Set<string>()
  return lines.filter((line) => {
    if (shelfKind(line.shelfLabel) !== 'extra') return false
    const key = productEan(line) ?? `gazetka:${line.ean}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

/** Linia pliku .PRO: EAN artykułu z kartoteki i cena promocji brutto. */
export type ProLine = { ean: string; price: number; name: string }

/**
 * Plik cen promocji dla jednego okresu (od-do). {@code tariff}: kod taryfy do wpisania w programie sklepu
 * (TYDZGAZ + numer gazetki z kolumny TYDZIEŃ na dwóch cyfrach + numer pliku na dwóch). Pominięte pozycje: bez artykułu, bez ceny, a artykuł
 * z kilkoma EAN-ami z gazetki idzie raz ({@code conflicts}: artykuły z różnymi cenami, w pliku pierwsza).
 */
export type ProFile = {
  dateFrom: string
  dateTo: string
  fileName: string
  tariff: string
  lines: ProLine[]
  withoutProduct: LeafletLine[]
  withoutPrice: LeafletLine[]
  conflicts: string[]
}

const day = (iso: string) => String(Number(iso.slice(8, 10)))

/**
 * Pliki .PRO z pozycji do opracowania: jeden okres od-do = jeden plik ({@code 29-1.PRO} = od 29. do 1.), od
 * najwcześniejszego okresu, numerowane w kodzie taryfy od 01.
 */
export function proFiles(lines: LeafletLine[], week: number): ProFile[] {
  const groups = new Map<string, LeafletLine[]>()
  for (const line of lines) {
    if (!toProcess(line)) continue
    const key = `${line.dateFrom}|${line.dateTo}`
    groups.set(key, [...(groups.get(key) ?? []), line])
  }
  const names = new Set<string>()
  return [...groups.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([key, group], index) => {
      const [dateFrom, dateTo] = key.split('|')
      const byEan = new Map<string, ProLine>()
      const conflicts = new Set<string>()
      const withoutProduct: LeafletLine[] = []
      const withoutPrice: LeafletLine[] = []
      for (const line of group) {
        const ean = productEan(line)
        const price = promoPrice(line)
        if (!ean) withoutProduct.push(line)
        else if (price === null || price <= 0) withoutPrice.push(line)
        else {
          const known = byEan.get(ean)
          if (!known) byEan.set(ean, { ean, price, name: line.product?.name ?? line.name })
          else if (Math.abs(known.price - price) >= 0.005) conflicts.add(known.name)
        }
      }
      let fileName = `${day(dateFrom)}-${day(dateTo)}.PRO`
      // Ten sam dzień w różnych miesiącach dałby tę samą nazwę.
      if (names.has(fileName))
        fileName = `${dateFrom.slice(8, 10)}${dateFrom.slice(5, 7)}-${dateTo.slice(8, 10)}${dateTo.slice(5, 7)}.PRO`
      names.add(fileName)
      return {
        dateFrom,
        dateTo,
        fileName,
        tariff: `TYDZGAZ${String(week).padStart(2, '0')}${String(index + 1).padStart(2, '0')}`,
        lines: [...byEan.values()],
        withoutProduct,
        withoutPrice,
        conflicts: [...conflicts],
      }
    })
}

/**
 * Treść pliku .PRO jak z Impeka ({@code pliki/29-1.PRO}): w linii 20 znaków, EAN na 13 (krótszy z zerami z przodu)
 * i cena promocji w groszach na 7 cyfrach ({@code 2481500000000} + {@code 0002699} = 26,99 zł), CRLF.
 */
export function proContent(file: ProFile) {
  const text = file.lines
    .map((line) => `${line.ean.padStart(13, '0')}${String(Math.round(line.price * 100)).padStart(7, '0')}\r\n`)
    .join('')
  return new Blob([text], { type: 'text/plain' })
}
