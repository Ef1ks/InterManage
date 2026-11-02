package com.intermanage.backend.promotion.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Jedna promocja z raportu: artykuł, okres i ceny (puste, gdy raport ich nie podał). */
public record PromotionReportRow(
        String itm8,
        String ean,
        String name,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal catalogPurchasePrice,
        BigDecimal regularSalePrice,
        BigDecimal promoPrice,
        BigDecimal marginPercent) {
}
