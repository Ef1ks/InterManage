import type { ColumnKey } from '@/features/data-entry/grid-values'
import {
  activePromoPrice,
  type DataEntryRow,
  emptyRow,
  marginPercent,
  withCatalogProduct,
} from '@/features/data-entry/rows'
import type { SavedFolderSpec } from '@/lib/saved-folders'
import type { BaseDelivery, BaseDeliveryLine, SaleUnit } from '@/lib/types'
import type { BaseDeliveryView } from '@/store/baseDeliveryDraftSlice'

/** Rejon nomenklatury, z którego „Weryfikuj warzywa” i „Weryfikuj mięso pakowane” biorą linie całego dokumentu. */
export const VIEW_REGIONS: Record<Exclude<BaseDeliveryView, 'carriers'>, string> = {
  vegetables: '14',
  meat: '26',
}

export const VIEW_LABELS: Record<BaseDeliveryView, string> = {
  carriers: 'Nośniki',
  vegetables: 'Warzywa i owoce (rejon 14)',
  meat: 'Mięso pakowane (rejon 26)',
}

const UNITS: Record<string, SaleUnit> = { U: 'szt.', K: 'kg', L: 'L' }

/**
 * Linia dokumentu z bazy jako wiersz tabeli wprowadzania danych. Ilość to sztuki przyjęte (kartony × PAK, także 0,
 * gdy towar nie przyjechał), cena zakupu C_Cesja, VAT z dokumentu. Artykuł z kartoteki ma jej dane (nazwa, cena
 * sprzedaży, nomenklatura) i EAN z kartoteki; nowy - nazwę, markę, nomenklaturę, rodzaj sprzedaży i cenę dla klienta
 * (C_Publi) z dokumentu.
 */
export function rowFromBaseLine(line: BaseDeliveryLine): DataEntryRow {
  const unit = (line.saleUnit && UNITS[line.saleUnit]) || 'szt.'
  const subfamily =
    line.regionCode && line.familyCode && line.subfamilyCode
      ? `${line.regionCode}${line.familyCode}${line.subfamilyCode}`
      : null
  const ean = line.product?.catalogEan ?? line.ean ?? ''
  const row: DataEntryRow = {
    ...emptyRow(),
    ean,
    eanChecked: ean,
    supplierCode: line.itm8,
    cn: line.cn,
    quantity: line.quantity,
    unitNet: line.purchasePrice,
    name: line.name,
    brand: line.brand ?? '',
    vatPercent: line.vatRate,
    salePrice: line.retailPrice,
    subfamilyCode: subfamily,
    unit,
    netContent: unit === 'szt.' ? 1 : (line.saleContent ?? 1),
    invoiceName: line.name,
    invoiceUnit: unit,
    base: {
      lineId: line.id,
      itm8: line.itm8,
      carrier: line.carrier,
      supplierCode: line.supplierCode,
      coliOrdered: line.coliOrdered,
      coliPrepared: line.coliPrepared,
      pack: line.pack,
      deposit: line.deposit,
      catalogDeposit: line.catalogDeposit,
      retailPrice: line.retailPrice,
      bestBefore: line.bestBefore,
      requiredBestBefore: line.requiredBestBefore,
      shortDated: line.shortDated,
      regionCode: line.regionCode,
    },
  }
  if (!line.product) return row
  // Ilość, cena zakupu i VAT zostają z dokumentu.
  return { ...withCatalogProduct(row, line.product), ean, eanChecked: ean }
}

/** Wiersze widoku: linie wybranych nośników albo całego dokumentu z rejonu weryfikacji (warzywa, mięso). */
export function viewRows(delivery: BaseDelivery, view: BaseDeliveryView, carriers: string[]) {
  return viewLines(delivery, view, carriers).map(rowFromBaseLine)
}

export function viewLines(delivery: BaseDelivery, view: BaseDeliveryView, carriers: string[]) {
  if (view === 'carriers') return delivery.lines.filter((line) => carriers.includes(line.carrier))
  const region = VIEW_REGIONS[view]
  return delivery.lines.filter(
    (line) => line.regionCode === region || (line.product?.subfamilyCode ?? '').startsWith(region),
  )
}

/** Cena dla klienta z dokumentu bazy jako cena sugerowana (widok warzyw i mięsa pakowanego: ceny z opakowania). */
export function documentPrice(row: DataEntryRow) {
  const price = row.base?.retailPrice
  return price != null && price > 0 ? price : null
}

/**
 * Ujemna marża przy aktualnej cenie sprzedaży, na artykule bez trwającej promocji z ceną cadencier (niebieską):
 * baza ustawiła cenę niższą niż nam sprzedaje albo ktoś ustawił za tanio.
 */
export function negativeMargin(row: DataEntryRow) {
  if (!row.known || !row.cadencier || row.salePrice === null || activePromoPrice(row) !== null) return false
  if (row.catalog && row.catalog.salePrice !== row.salePrice) return false
  const margin = marginPercent(row)
  return margin !== null && margin < 0
}

/**
 * Nowy artykuł do założenia w kartotece: nie ma go w kartotece i ma prawdziwy EAN. Kod wewnętrzny sklepu (EAN-13
 * zaczynający się od 2: towar ważony, etykiety z wagi) i linia bez EAN-u (stojaki, same zera) nie są nowymi
 * artykułami, tak jak w zestawieniu z Impeka.
 */
export function isNewArticle(row: DataEntryRow) {
  if (row.known) return false
  const ean = row.ean.replace(/\D/g, '')
  return ean.replace(/^0+/, '') !== '' && !(ean.length === 13 && ean.startsWith('2'))
}

/** Towar nie przyjechał: zamówione kartony, 0 przygotowanych. */
export function notDelivered(row: DataEntryRow) {
  return row.base !== null && row.base !== undefined && row.base.coliPrepared === 0
}

/** Klucz dostawcy z bazy: kod bez zer wiodących (00538 = 538). */
export const supplierKey = (code: string | null | undefined) => (code ?? '').replace(/^0+/, '')

/**
 * Nowe artykuły całego dokumentu (bez artykułu w kartotece), po jednym na ITM8, według dostawcy (538 baza, 88110
 * alkohol): do osobnych plików CADBEZP. Wiersz z tabeli ma pierwszeństwo (poprawki), inaczej dane z dokumentu.
 */
export function newArticlesBySupplier(delivery: BaseDelivery, tableRows: DataEntryRow[]) {
  const fromTable = new Map(tableRows.flatMap((row) => (row.base ? [[row.base.lineId, row] as const] : [])))
  const seen = new Set<string>()
  const groups = new Map<string, DataEntryRow[]>()
  for (const line of delivery.lines) {
    if (line.product || seen.has(line.itm8)) continue
    seen.add(line.itm8)
    const row = fromTable.get(line.id) ?? rowFromBaseLine(line)
    if (!isNewArticle(row)) continue
    const key = supplierKey(line.supplierCode)
    groups.set(key, [...(groups.get(key) ?? []), row])
  }
  return [...groups.entries()].map(([supplierCode, rows]) => ({ supplierCode, rows }))
}

/** Rejony nomenklatury, których EAN-y idą do pliku „Sprawdź PLU”. */
export const PLU_REGIONS = ['32', '33']

/** Folder pliku .lst „Sprawdź PLU”, wybrany raz w przeglądarce (Ustawienia zapisu). */
export const PLU_FOLDER: SavedFolderSpec = { key: 'base-delivery-plu', label: 'Sprawdź PLU (plik .lst)' }

/**
 * EAN-y artykułów całego dokumentu z rejonów 32 i 33 (rejon z dokumentu albo z kartoteki), bez powtórzeń, w kolejności
 * dokumentu; artykuł z kartoteki ma jej EAN, nowy EAN z dokumentu.
 */
export function pluEans(delivery: BaseDelivery) {
  const eans = new Set<string>()
  for (const line of delivery.lines) {
    const region = line.regionCode ?? line.product?.subfamilyCode?.slice(0, 2) ?? null
    const fromCatalog = PLU_REGIONS.some((code) => (line.product?.subfamilyCode ?? '').startsWith(code))
    if (!fromCatalog && (region === null || !PLU_REGIONS.includes(region))) continue
    const ean = line.product?.catalogEan ?? line.ean
    if (ean && /^\d{8,14}$/.test(ean)) eans.add(ean)
  }
  return [...eans]
}

/** Pusta wartość w filtrze kolumny. */
export const EMPTY_VALUE = '(puste)'

export type ColumnFilters = Partial<Record<ColumnKey, string[]>>
