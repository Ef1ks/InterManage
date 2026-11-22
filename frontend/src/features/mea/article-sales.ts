import { addDays } from '@/lib/dates'
import type { MeaArticleSales, MeaPromoSource } from '@/lib/types'

/** Dzień sprzedaży; `quantity` null = dzień po ostatniej wgranej sprzedaży (jeszcze nie wiemy). */
export type SalesDay = {
  date: string
  quantity: number | null
  gross: number | null
  /** Skąd wiemy, że tego dnia artykuł był w promocji (puste = bez promocji). */
  promos: string[]
}

/** Tydzień czwartek-środa (tydzień gazetkowy). */
export type SalesWeek = {
  from: string
  to: string
  quantity: number
  gross: number
  promos: string[]
  /** Część dni nie ma jeszcze wgranej sprzedaży. */
  partial: boolean
  days: SalesDay[]
}

const SOURCE_LABELS: Record<MeaPromoSource, string> = {
  LIST: 'lista promocji',
  TILL: 'promocja z kasy',
  LEAFLET: 'gazetka sklepu',
  MEA: 'gazetka MEA',
}

const PRICE_DROP = 'cena niższa o ponad 15%'

export const itm8Key = (itm8: string) => itm8.replace(/^0+/, '')

/** Tygodnie artykułu od najstarszego; bez artykułu w odpowiedzi tygodnie są puste (bez sprzedaży). */
export function salesWeeks(data: MeaArticleSales, itm8: string): SalesWeek[] {
  const article = data.articles.find((item) => itm8Key(item.itm8) === itm8Key(itm8))
  const byDate = new Map((article?.days ?? []).map((day) => [day.date, day]))
  const promos = article?.promos ?? []
  const weeks: SalesWeek[] = []
  for (let from = data.from; from <= data.to; from = addDays(from, 7)) {
    const days: SalesDay[] = []
    for (let index = 0; index < 7; index++) {
      const date = addDays(from, index)
      const sale = byDate.get(date)
      const known = data.lastSaleDate != null && date <= data.lastSaleDate
      const labels = promos
        .filter((promo) => promo.from <= date && promo.to >= date)
        .map((promo) => (promo.source === 'MEA' && promo.label ? `MEA ${promo.label}` : SOURCE_LABELS[promo.source]))
      if (sale?.priceDrop) labels.push(PRICE_DROP)
      days.push({
        date,
        quantity: known ? (sale?.quantity ?? 0) : null,
        gross: known ? (sale?.gross ?? 0) : null,
        promos: [...new Set(labels)],
      })
    }
    weeks.push({
      from,
      to: addDays(from, 6),
      quantity: days.reduce((sum, day) => sum + (day.quantity ?? 0), 0),
      gross: days.reduce((sum, day) => sum + (day.gross ?? 0), 0),
      promos: [...new Set(days.flatMap((day) => day.promos))],
      partial: days.some((day) => day.quantity == null),
      days,
    })
  }
  return weeks
}

/** Średnia cena brutto sztuki (sprzedaż / ilość); bez sprzedaży null. */
export const averagePrice = (gross: number | null, quantity: number | null) =>
  gross != null && quantity ? gross / quantity : null
