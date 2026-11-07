package com.intermanage.backend.stocktake.dto;

import java.math.BigDecimal;

/** Artykuł z raportu porównania stanów. {@code differenceValue} to wartość różnicy w cenach zakupu. */
public record StocktakeReportRow(
        String itm8,
        String ean,
        String name,
        String brand,
        String blockType,
        BigDecimal realQuantity,
        BigDecimal theoreticalQuantity,
        BigDecimal differenceQuantity,
        BigDecimal differenceValue,
        String articleKind) {
}
