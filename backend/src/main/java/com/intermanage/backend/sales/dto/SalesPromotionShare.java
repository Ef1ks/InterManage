package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;

/**
 * Sprzedaż brutto z dni, w których artykuły były w promocji, w okresie porównania i aktualnym, z liczbą artykułów,
 * które ją dały. Udział w całej sprzedaży i zmianę liczy front z sum porównania.
 */
public record SalesPromotionShare(
        BigDecimal previousSaleValueGross,
        BigDecimal currentSaleValueGross,
        long previousArticles,
        long currentArticles) {
}
