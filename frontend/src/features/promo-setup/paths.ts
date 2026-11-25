/** Ustawianie promocji (uprawnienie jak wprowadzanie danych). */
export const promoSetupPath = '/ustaw-promocje'

/**
 * Stan nawigacji z wprowadzania danych i z promocji z kolektora: kody artykułów do dopisania do promocji (tryb makra).
 * Z kolektora przychodzą też ceny i końce ({@code promoItems}, pod kodem z {@code promoCodes}) i początek operacji.
 */
export type PromoSetupHandoff = {
  promoCodes: string[]
  promoItems?: Record<string, { price: number; dateTo: string }>
  promoStart?: string | null
  /** Skąd artykuły (do komunikatu) i id promocji z kolektora do oznaczenia jako wstawione. */
  promoSource?: { label: string; collectorPromotionId?: string }
}
