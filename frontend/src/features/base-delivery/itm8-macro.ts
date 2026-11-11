import type { Itm8MacroItem } from '@/features/data-entry/mercalys-macro'
import type { BaseDelivery, BaseDeliveryLine } from '@/lib/types'

import { supplierKey } from './base-rows'

/** Data usunięcia ITM8 = data dokumentu + tyle lat. */
export const DELETE_AFTER_YEARS = 2

/** Pozycja makra razem z linią dokumentu, z której jest (do podglądu w oknie). */
export type PlannedItem = Itm8MacroItem & { line: BaseDeliveryLine }

export type SkipReason = 'MISSING' | 'NO_EAN'

export type MacroPlan = {
  items: PlannedItem[]
  /** Kody pominięte: nie ma ich w dokumencie albo linia nie ma EAN-u (bez niego nie da się otworzyć karty). */
  skipped: { code: string; reason: SkipReason }[]
}

/** Kody z wklejonego tekstu (nowa linia, przecinek, średnik, spacja, tabulator), bez powtórzeń, w kolejności. */
export function parseItm8List(text: string) {
  const seen = new Set<string>()
  const codes: string[] = []
  for (const raw of text.split(/[\s,;]+/)) {
    const key = itm8Key(raw)
    if (!key || seen.has(key)) continue
    seen.add(key)
    codes.push(key)
  }
  return codes
}

/** ITM8 bez zer wiodących i znaków spoza cyfr: w pliku z bazy 8 cyfr, w kartotece 13 z zerami. */
const itm8Key = (code: string) => code.replace(/\D/g, '').replace(/^0+/, '')

/**
 * Paruje kody z liniami dokumentu (pierwsza linia z danym ITM8, ten sam artykuł bywa na kilku nośnikach). Kilka ITM8
 * pod jednym EAN-em (np. Ferrero Rocher pod trzema kodami z bazy) to kilka wpisów „Kod ITM8” na tej samej karcie.
 */
export function planMacro(delivery: BaseDelivery, codes: string[]): MacroPlan {
  const lines = new Map<string, BaseDeliveryLine>()
  for (const line of delivery.lines) {
    const key = itm8Key(line.itm8)
    if (key && !lines.has(key)) lines.set(key, line)
  }
  const deleteDate = deleteDateOf(delivery.deliveryDate)
  const plan: MacroPlan = { items: [], skipped: [] }
  for (const code of codes) {
    const line = lines.get(itm8Key(code))
    if (!line) {
      plan.skipped.push({ code, reason: 'MISSING' })
    } else if (!line.ean?.replace(/^0+/, '')) {
      plan.skipped.push({ code, reason: 'NO_EAN' })
    } else {
      plan.items.push({
        line,
        itm8: itm8Key(line.itm8),
        ean: line.ean,
        supplierCode: supplierKey(line.supplierCode),
        pack: line.pack,
        deleteDate,
        purchasePrice: line.purchasePrice,
      })
    }
  }
  return plan
}

/** Data dokumentu (RRRR-MM-DD) + {@link DELETE_AFTER_YEARS} lat, RRRR-MM-DD. 29 lutego w roku bez niego daje 28 lutego. */
export function deleteDateOf(isoDate: string) {
  const [year, month, day] = isoDate.slice(0, 10).split('-').map(Number)
  const targetYear = year + DELETE_AFTER_YEARS
  const lastDay = new Date(Date.UTC(targetYear, month, 0)).getUTCDate()
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${targetYear}-${pad(month)}-${pad(Math.min(day, lastDay))}`
}
