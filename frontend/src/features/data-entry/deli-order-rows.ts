import { parseQuantity } from '@/features/deli-order/QuantityInput'
import type { DataEntryCatalogProduct, DeliOrder, DeliOrderItem } from '@/lib/types'

import { type DataEntryRow, emptyRow, withScannedProduct } from './rows'

const withoutZeros = (code: string) => code.replace(/^0+/, '')

/** Pozycje zamówienia garmażu jako wiersze tabeli: dane z kartoteki jak po wpisaniu EAN-u, ilość z zamówienia. */
export function rowsFromOrder(
  lines: { item: DeliOrderItem; quantity: number }[],
  products: DataEntryCatalogProduct[],
): DataEntryRow[] {
  const byEan = new Map(products.map((product) => [product.ean, product]))
  return lines.map(({ item, quantity }) => {
    const row = { ...emptyRow(), ean: item.ean, name: item.name ?? '', brand: item.brand ?? '', quantity }
    return { ...withScannedProduct(row, byEan.get(withoutZeros(item.ean)) ?? null), quantity }
  })
}

export type OrderFulfilment = {
  /** Wszystkie pozycje zamówienia ze zrealizowanymi ilościami, jak z okna „Zrealizowane ilości”. */
  items: { id: string; quantity: number }[]
  /** Pozycje zamówienia, których towaru nie ma w tabeli: realizują się z ilością 0. */
  missing: number
  /** Wiersze tabeli spoza zamówienia (z garmażem jedzie też inny towar): nie wchodzą do realizacji. */
  outside: number
  /** Ilości, których zamówienie nie przyjmie (np. ułamek przy sztukach). */
  errors: string[]
}

/**
 * Zrealizowane ilości z tabeli. Wiersz należy do pozycji po EAN-ie albo ITM8 (zeskanowany multikod ma inny EAN, ale
 * ITM8 z kartoteki), kilka wierszy jednego artykułu się sumuje. Artykuł bywa w kilku kategoriach zamówienia: suma
 * wypełnia pozycje po kolei, a nadwyżka trafia do ostatniej. Znak pomijamy, bo przed protokołem tabelę można odwrócić
 * („Zmień znak”).
 */
export function orderFulfilment(order: DeliOrder, rows: DataEntryRow[]): OrderFulfilment {
  const groups = new Map<string, DeliOrderItem[]>()
  const productByCode = new Map<string, string>()
  for (const item of order.items) {
    groups.set(item.productId, [...(groups.get(item.productId) ?? []), item])
    if (item.ean) productByCode.set(`ean:${withoutZeros(item.ean)}`, item.productId)
    if (item.itm8) productByCode.set(`itm8:${withoutZeros(item.itm8)}`, item.productId)
  }

  const totals = new Map<string, number>()
  let outside = 0
  for (const row of rows) {
    if (row.ean === '' && row.name === '') continue
    const productId =
      productByCode.get(`ean:${withoutZeros(row.ean)}`) ?? productByCode.get(`itm8:${withoutZeros(row.itm8 ?? '')}`)
    if (productId) totals.set(productId, (totals.get(productId) ?? 0) + (row.quantity ?? 0))
    else outside++
  }

  const items: OrderFulfilment['items'] = []
  const errors: string[] = []
  let missing = 0
  for (const [productId, group] of groups) {
    if (!totals.has(productId)) missing += group.length
    let left = Math.round(Math.abs(totals.get(productId) ?? 0) * 1000) / 1000
    group.forEach((item, index) => {
      const quantity = index === group.length - 1 ? left : Math.min(left, item.quantity)
      left = Math.round((left - quantity) * 1000) / 1000
      const { error } = parseQuantity(String(quantity), item.unit)
      if (error) errors.push(`${item.name ?? item.ean}: ${error.toLowerCase()}`)
      items.push({ id: item.id, quantity })
    })
  }
  return { items, missing, outside, errors }
}
