/**
 * Marża jak w Mercalys: zysk netto (sprzedaż netto − zakup netto) w procentach ceny sprzedaży brutto. Przykład:
 * zakup 2,49 zł, VAT 5%, sprzedaż 3,49 zł: (3,3238 − 2,49) / 3,49 = 23,89%. Ten sam wzór na serwerze
 * ({@code common/component/Margin}).
 */
export function marginOf(saleGross: number, vatPercent: number, purchase: number) {
  return ((saleGross / (1 + vatPercent / 100) - purchase) / saleGross) * 100
}

/** Marża % z sum: marża w zł w procentach sprzedaży brutto; null bez sprzedaży. */
export function marginShare(marginValue: number | null | undefined, saleGross: number | null | undefined) {
  return marginValue == null || !saleGross ? null : (marginValue / saleGross) * 100
}

/**
 * Cena brutto, przy której zakup netto daje marżę {@code required} %: zakup × (1 + VAT) / (1 − marża × (1 + VAT)).
 * Null, gdy marży nie da się osiągnąć (marża ≥ 100 / (1 + VAT)).
 */
export function salePriceFor(purchase: number, vatPercent: number, required: number) {
  const grossFactor = 1 + vatPercent / 100
  const denominator = 1 - (required / 100) * grossFactor
  return denominator <= 0 ? null : (purchase * grossFactor) / denominator
}
