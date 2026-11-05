import { type ProFile, type ProLine, uniqueProFileName } from '@/features/leaflet/leaflet-rows'
import type { PromoRow, PromoSetupDraft, PromoTile } from '@/store/promoSetupDraftSlice'

export const codeKey = (code: string) => code.replace(/^0+/, '')

/** Cena po przecenie o {@code discount} % od ceny zwykłej, do grosza; {@code null} bez ceny zwykłej. */
export function discountedPrice(regularPrice: number | null, discount: number) {
  return regularPrice === null ? null : Math.round(regularPrice * (100 - discount)) / 100
}

/** Marża % od sprzedaży netto przy cenie promocji i ostatniej cenie zakupu. */
export function promoMargin({ article, price }: PromoRow) {
  if (!article || price === null || price <= 0 || article.vatPercent === null || article.lastPurchasePrice === null) {
    return null
  }
  const net = price / (1 + article.vatPercent / 100)
  return ((net - article.lastPurchasePrice) / net) * 100
}

/** Kafelek z artykułami i pełnym okresem daje plik. */
export const hasPeriod = (tile: PromoTile) => tile.dateTo !== '' && tile.dateTo >= tile.dateFrom

const tariff = (operation: string, tile: PromoTile) => `${operation}${String(tile.number).padStart(2, '0')}`

export const tileTariff = (draft: PromoSetupDraft, tile: PromoTile) => tariff(draft.operation, tile)

function tileFile(operation: string, tile: PromoTile, names: Set<string>): ProFile {
  const byEan = new Map<string, ProLine>()
  const conflicts = new Set<string>()
  const withoutProduct: { name: string }[] = []
  const withoutPrice: { name: string }[] = []
  for (const row of tile.rows) {
    const { article, price } = row
    if (!article?.ean) withoutProduct.push({ name: article?.name ?? row.code })
    else if (price === null || price <= 0) withoutPrice.push({ name: article.name ?? article.ean })
    else {
      const key = codeKey(article.ean)
      const known = byEan.get(key)
      if (!known) byEan.set(key, { ean: article.ean.slice(-13), price, name: article.name ?? article.ean })
      else if (Math.abs(known.price - price) >= 0.005) conflicts.add(known.name)
    }
  }
  return {
    dateFrom: tile.dateFrom,
    dateTo: tile.dateTo,
    fileName: uniqueProFileName(tile.dateFrom, tile.dateTo, names),
    tariff: tariff(operation, tile),
    lines: [...byEan.values()],
    withoutProduct,
    withoutPrice,
    conflicts: [...conflicts],
  }
}

/**
 * Pliki .PRO kafelków z artykułami i okresem, pod id kafelka. Nazwy liczone razem dla wszystkich, żeby kafelki
 * z tym samym okresem nie nadpisały sobie pliku także przy zapisie po jednym.
 */
export function promoFiles(draft: PromoSetupDraft) {
  const names = new Set<string>()
  return new Map(
    draft.tiles
      .filter((tile) => tile.rows.length > 0 && hasPeriod(tile))
      .map((tile) => [tile.id, tileFile(draft.operation, tile, names)]),
  )
}

/** Kod z pola: tekst z pierwszej kolumny każdej wklejonej linii (Excel), bez pustych. */
export const pastedCodes = (text: string) =>
  text
    .split(/\r?\n/)
    .map((line) => line.split('\t')[0].trim())
    .filter(Boolean)
