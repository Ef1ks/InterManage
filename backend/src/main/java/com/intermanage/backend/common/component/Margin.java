package com.intermanage.backend.common.component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Marża jak w Mercalys: zysk netto (sprzedaż netto − zakup netto) w procentach ceny sprzedaży <b>brutto</b>. Przykład:
 * zakup 2,49 zł, VAT 5%, sprzedaż 3,49 zł: (3,3238 − 2,49) / 3,49 = 23,89%. Tak liczą raporty z systemu sklepu
 * (sprzedaż, dokumenty), więc tak liczy cały program.
 */
public final class Margin {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Margin() {
    }

    /** Cena netto z ceny brutto i stawki VAT w procentach. */
    public static BigDecimal netOf(BigDecimal gross, BigDecimal vatPercent) {
        return gross.multiply(HUNDRED).divide(HUNDRED.add(vatPercent), 6, RoundingMode.HALF_UP);
    }

    /** Marża % przy cenie sprzedaży brutto {@code saleGross} i zakupie netto sztuki {@code purchase}. */
    public static BigDecimal percent(BigDecimal saleGross, BigDecimal vatPercent, BigDecimal purchase, int scale) {
        return netOf(saleGross, vatPercent).subtract(purchase).multiply(HUNDRED)
                .divide(saleGross, scale, RoundingMode.HALF_UP);
    }

    /** Najwyższa cena zakupu netto, przy której cena brutto {@code saleGross} daje marżę {@code required} %. */
    public static BigDecimal targetPurchase(BigDecimal saleGross, BigDecimal vatPercent, BigDecimal required) {
        return netOf(saleGross, vatPercent).subtract(saleGross.multiply(required).divide(HUNDRED, 6, RoundingMode.HALF_UP))
                .setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Cena sprzedaży brutto, przy której zakup netto {@code purchase} daje marżę {@code required} %:
     * zakup × (100 + VAT) / (100 − marża × (100 + VAT) / 100), jednym dzieleniem (pośrednie zaokrąglenie podbijałoby cenę
     * o grosz). {@code null}, gdy marży nie da się osiągnąć (marża ≥ 100 / (1 + VAT)).
     */
    public static BigDecimal salePrice(BigDecimal purchase, BigDecimal vatPercent, BigDecimal required, int scale,
            RoundingMode rounding) {
        BigDecimal grossFactor = HUNDRED.add(vatPercent);
        BigDecimal denominator = HUNDRED.multiply(HUNDRED).subtract(required.multiply(grossFactor));
        if (denominator.signum() <= 0) {
            return null;
        }
        return purchase.multiply(HUNDRED).multiply(grossFactor).divide(denominator, scale, rounding);
    }
}
