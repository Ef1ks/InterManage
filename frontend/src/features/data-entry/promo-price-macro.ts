import type { PromoPriceAdd } from './api'
import { mercalysVatCode } from './mercalys-macro'
import { activePromoPrice, type DataEntryRow, type Pricing, suggestedPrice } from './rows'

/** Artykuł z cadencier w trwającej promocji: cenę ma z centrali, więc jej nie zmieniamy, mimo promocji. */
export const cadencierOnPromo = (row: DataEntryRow) => activePromoPrice(row) !== null && row.cadencier === true

export type PromoPricePlan = {
  items: PromoPriceAdd['items']
  /** Artykuły w promocji z cadencier, pominięte celowo. */
  cadencier: number
  /** Artykuły w promocji bez ITM8, EAN-u, ceny sugerowanej albo stawki VAT. */
  incomplete: number
}

/**
 * Artykuły w trwającej promocji z kasy: zmiana cen z pliku ich nie obejmuje, więc cenę zwykłą (cena sugerowana z tabeli)
 * wpisuje makro w taryfie od dnia wprowadzenia. Każdy EAN raz; artykuły z cadencier są pomijane.
 */
export function planPromoPrices(rows: DataEntryRow[], pricing: Pricing): PromoPricePlan {
  const items = new Map<string, PromoPriceAdd['items'][number]>()
  let cadencier = 0
  let incomplete = 0
  for (const row of rows) {
    const promo = activePromoPrice(row)
    if (promo === null) continue
    if (cadencierOnPromo(row)) {
      cadencier += 1
      continue
    }
    const price = suggestedPrice(row, pricing)
    const vatCode = mercalysVatCode(row.vatPercent)
    if (row.eanFallback || !/^\d{1,14}$/.test(row.ean) || !row.itm8 || price === null || price <= 0 || !vatCode) {
      incomplete += 1
      continue
    }
    if (!items.has(row.ean)) {
      items.set(row.ean, {
        ean: row.ean,
        itm8: row.itm8,
        name: row.name,
        salePrice: price,
        vatCode,
        promoPrice: promo,
        promoTo: row.promoTo ?? null,
      })
    }
  }
  return { items: [...items.values()], cadencier, incomplete }
}
