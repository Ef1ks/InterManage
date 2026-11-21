package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;

/**
 * Sprzedaż brutto i netto oraz marża z dni, w których artykuły były w promocji, w okresie porównania i aktualnym, z
 * liczbą artykułów, które je dały. Udział w całej sprzedaży i marży oraz zmiany liczy front z sum porównania.
 */
public record SalesPromotionShare(
        BigDecimal previousSaleValueGross,
        BigDecimal currentSaleValueGross,
        BigDecimal previousSaleValueNet,
        BigDecimal currentSaleValueNet,
        BigDecimal previousMarginValue,
        BigDecimal currentMarginValue,
        long previousArticles,
        long currentArticles) {
}
