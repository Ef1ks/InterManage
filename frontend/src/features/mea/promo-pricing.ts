import type { MeaDocumentLine } from '@/lib/types'

/**
 * Cena zwykła i promocyjna pozycji MEA do pokazania obok siebie z rabatem w %.
 *
 * Cena zwykła (`standard`): półka CAD z formularza (cena z cadencier), bez niej CENA_CAD z kartoteki, na końcu cena
 * sprzedaży z kartoteki, o ile nie jest to trwająca promocja z kasy.
 *
 * Cena promocyjna (`promo`) to cena dla klienta z opisu gazetki, bez opisu półka MEA z formularza. `perUnit` to cena
 * jednej sztuki przy pełnej promocji, z niej liczy się `percent`:
 * - „X szt. za Y zł”: Y / X,
 * - „X + Y (szt.)” (2 + 1): cena zwykła × X / (X + Y),
 * - „X + Y (%)” (1 + 50,00%): średnio za sztukę przy X pełnych i Y z rabatem,
 * - reszta: cena promocyjna.
 */
export type PromoPricing = {
  standard: number | null
  standardSource: string | null
  promo: number | null
  perUnit: number | null
  percent: number | null
  /** Krótki opis mechanizmu („2 + 1”, „2 szt. za 17,98 zł”, „2. szt. −50%”, „rabat przy kasie”). */
  mechanism: string | null
}

const money = (value: number) => value.toLocaleString('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

/** „2 + 1” → [2, 1]; „1 + 50,00%” → [1, 50]. */
function variantNumbers(variant: string | null): [number, number] | null {
  const match = variant?.match(/^\s*(\d+)\s*\+\s*(\d+(?:[.,]\d+)?)\s*%?\s*$/)
  if (!match) return null
  return [Number(match[1]), Number(match[2].replace(',', '.'))]
}

export function promoPricing(line: MeaDocumentLine): PromoPricing {
  const product = line.product
  let standard: number | null = null
  let standardSource: string | null = null
  if (line.parallelShelfPrice) {
    standard = line.parallelShelfPrice
    standardSource = 'półka CAD z formularza MEA'
  } else if (product?.cadencierPrice) {
    standard = product.cadencierPrice
    standardSource = 'CENA_CAD z kartoteki'
  } else if (product?.salePrice && product.promoPrice == null) {
    standard = product.salePrice
    standardSource = 'cena sprzedaży z kartoteki'
  }

  const leaflet = line.leaflet
  const promo = leaflet?.clientPrice ?? line.shelfPrice ?? null
  let perUnit = promo
  let mechanism: string | null = null
  const kind = leaflet?.megaKind?.toLowerCase() ?? ''
  const numbers = variantNumbers(leaflet?.megaVariant ?? null)

  if (kind.startsWith('x szt') && leaflet?.megaQuantity) {
    if (leaflet.megaPrice) {
      perUnit = leaflet.megaPrice / leaflet.megaQuantity
      mechanism = `${leaflet.megaQuantity} szt. za ${money(leaflet.megaPrice)} zł`
    } else {
      mechanism = `od ${leaflet.megaQuantity} szt.`
    }
  } else if (kind.includes('(szt') && numbers) {
    const [paid, free] = numbers
    mechanism = `${paid} + ${free}`
    perUnit = standard != null ? (standard * paid) / (paid + free) : null
  } else if (kind.includes('(%)') && numbers) {
    const [paid, discount] = numbers
    mechanism = `${paid + 1}. szt. −${discount.toLocaleString('pl-PL', { maximumFractionDigits: 2 })}%`
    // „1 + 50%”: jedna pełna, druga z rabatem.
    perUnit = standard != null ? (standard * (paid + (1 - discount / 100))) / (paid + 1) : null
  } else if (kind.includes('kasie')) {
    mechanism = 'rabat przy kasie'
  }

  const percent = standard && perUnit != null ? (1 - perUnit / standard) * 100 : null
  return { standard, standardSource, promo, perUnit, percent, mechanism }
}

/** Oznaczenie wymaga karty lojalnościowej albo aplikacji (MOJE INTERMARCHE). */
export const isCardOnly = (mark: string) => /kart|aplikacj|moje i/i.test(mark)
