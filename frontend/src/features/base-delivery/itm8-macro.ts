import type { BaseDelivery, BaseDeliveryLine } from '@/lib/types'

import { supplierKey } from './base-rows'

/**
 * Makro dla odtwarzacza AutoHotkey (InterClicker, F9 czyta schowek linia po linii): wpisanie kodów ITM8 artykułom
 * z dokumentu dostawy z bazy w programie sklepu. Myszy używamy tylko tam, gdzie nie da się dojść klawiaturą
 * (kliknięcia są zawodne, gdy okno albo lista się przesunie). Współrzędne są względem całego ekranu stanowiska
 * (F6 w odtwarzaczu pokazuje bieżące X, Y), czasy w ms. Poprawiamy je tylko tutaj.
 */
export const MACRO_CONFIG = {
  /** Menu „Artykuł”, raz na początku. */
  articleMenu: { x: 380, y: 14, sleep: 300 },
  /** Pozycje kroku dla jednego ITM8, w kolejności. */
  articleCard: { x: 53, y: 66, sleep: 300 },
  /** Tab do pola EAN. */
  toEanField: 150,
  afterEan: 300,
  itm8Tab: { x: 254, y: 117, sleep: 200 },
  /** Tab do pola ITM8 na zakładce. */
  toItm8Field: 150,
  /** Po Enterze w kolejnych polach karty (ITM8, pusty Enter, dostawca, pusty Enter, ITM8 ponownie). */
  afterField: 200,
  /** Między pierwszym a drugim wpisaniem ilości w opakowaniu. */
  betweenPack: 150,
  afterPack: 200,
  afterExpiry: 150,
  /** Klawisze zamykające i zatwierdzające kartę (dawniej trzy kliknięcia). */
  closeKeys: [
    { key: '{Enter}', sleep: 300 },
    { key: '{Space}', sleep: 300 },
    { key: '{Enter}', sleep: 400 },
  ],
  /** Okno ceny zakupu: trzy Entery do pola ceny, cena, Enter, Enter. */
  beforePrice: 200,
  afterItem: 400,
  /** Termin na karcie = data dokumentu + tyle lat. */
  expiryYears: 2,
} as const

/** Dane pozycji makra wzięte z linii dokumentu. */
export type MacroItem = {
  itm8: string
  ean: string
  supplierCode: string
  packQuantity: number
  expiryDateFormatted: string
  /** Cena zakupu netto sztuki (C_Cesja) z przecinkiem, jak wpisuje się ją w programie sklepu. */
  purchasePrice: string
}

export type MacroPlan = {
  items: MacroItem[]
  /** Wklejone kody, których nie ma w dokumencie. */
  missing: string[]
  /** Kody z dokumentu, którego linia nie ma EAN-u (bez niego nie da się otworzyć karty). */
  withoutEan: string[]
}

/** Kody z wklejonego tekstu (nowa linia, przecinek, średnik, spacja, tabulator), bez powtórzeń, w kolejności. */
export function parseItm8List(text: string) {
  const seen = new Set<string>()
  const codes: string[] = []
  for (const raw of text.split(/[\s,;]+/)) {
    const code = raw.trim()
    const key = itm8Key(code)
    if (!key || seen.has(key)) continue
    seen.add(key)
    codes.push(code)
  }
  return codes
}

/** ITM8 bez zer wiodących i znaków spoza cyfr: w pliku z bazy 8 cyfr, w kartotece 13 z zerami. */
const itm8Key = (code: string) => code.replace(/\D/g, '').replace(/^0+/, '')

/** Paruje kody z liniami dokumentu (pierwsza linia z danym ITM8, ten sam artykuł bywa na kilku nośnikach). */
export function planMacro(delivery: BaseDelivery, codes: string[]): MacroPlan {
  const lines = new Map<string, BaseDeliveryLine>()
  for (const line of delivery.lines) {
    const key = itm8Key(line.itm8)
    if (key && !lines.has(key)) lines.set(key, line)
  }
  const expiry = expiryDate(delivery.deliveryDate)
  const plan: MacroPlan = { items: [], missing: [], withoutEan: [] }
  for (const code of codes) {
    const line = lines.get(itm8Key(code))
    if (!line) {
      plan.missing.push(code)
    } else if (!line.ean) {
      plan.withoutEan.push(code)
    } else {
      plan.items.push({
        itm8: code,
        ean: line.ean,
        supplierCode: supplierKey(line.supplierCode),
        packQuantity: line.pack,
        expiryDateFormatted: expiry,
        purchasePrice: formatPrice(line.purchasePrice),
      })
    }
  }
  return plan
}

/**
 * Data dokumentu (RRRR-MM-DD) + {@link MACRO_CONFIG.expiryYears} lat jako DDMMRRRR. 29 lutego w roku bez niego
 * daje 28 lutego, nie 1 marca.
 */
export function expiryDate(isoDate: string) {
  const [year, month, day] = isoDate.split('-').map(Number)
  const targetYear = year + MACRO_CONFIG.expiryYears
  const lastDay = new Date(Date.UTC(targetYear, month, 0)).getUTCDate()
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${pad(Math.min(day, lastDay))}${pad(month)}${targetYear}`
}

/** Cena z dwoma miejscami po przecinku (1.5 → „1,50”). */
const formatPrice = (price: number) => price.toFixed(2).replace('.', ',')

/** Skrypt makra: nagłówek raz, potem kroki dla każdej pozycji; linie z `#` odtwarzacz pomija. CRLF jak w Windows. */
export function buildMacro(items: MacroItem[]) {
  const c = MACRO_CONFIG
  const out: string[] = []
  const click = (point: { x: number; y: number; sleep: number }) =>
    out.push(`CLICK|${point.x}|${point.y}`, `SLEEP|${point.sleep}`)
  const key = (name: string, sleep: number) => out.push(`KEY|${name}`, `SLEEP|${sleep}`)
  const enter = (text: string | number, sleep: number) => out.push(`TEXT|${text}`, 'KEY|{Enter}', `SLEEP|${sleep}`)

  out.push(`# Uzupełnij ITM8: ${items.length} poz.`)
  click(c.articleMenu)
  for (const item of items) {
    out.push(`# ITM8 ${item.itm8} · EAN ${item.ean}`)
    click(c.articleCard)
    key('{Tab}', c.toEanField)
    enter(item.ean, c.afterEan)
    click(c.itm8Tab)
    key('{Tab}', c.toItm8Field)
    enter(item.itm8, c.afterField)
    key('{Enter}', c.afterField)
    enter(item.supplierCode, c.afterField)
    key('{Enter}', c.afterField)
    enter(item.itm8, c.afterField)
    enter(item.packQuantity, c.betweenPack)
    enter(item.packQuantity, c.afterPack)
    out.push(`TEXT|${item.expiryDateFormatted}`, `SLEEP|${c.afterExpiry}`)
    c.closeKeys.forEach((step) => key(step.key, step.sleep))
    out.push('# cena zakupu netto za 1 szt.')
    key('{Enter}', c.beforePrice)
    key('{Enter}', c.beforePrice)
    key('{Enter}', c.beforePrice)
    enter(item.purchasePrice, c.beforePrice)
    key('{Enter}', c.afterItem)
  }
  return out.join('\r\n') + '\r\n'
}
