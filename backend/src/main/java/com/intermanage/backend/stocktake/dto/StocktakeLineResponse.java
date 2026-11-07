package com.intermanage.backend.stocktake.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Artykuł z różnicą. {@code firstValue} to różnica z pierwszego wydruku (inna niż {@code value} po przeliczeniu),
 * {@code soldAfter} i {@code deliveredAfter} to sprzedaż i dostawy od inwentury.
 */
public record StocktakeLineResponse(
        String itm8,
        String ean,
        String name,
        String brand,
        String regionCode,
        String regionName,
        BigDecimal realQuantity,
        BigDecimal theoreticalQuantity,
        BigDecimal differenceQuantity,
        BigDecimal value,
        BigDecimal firstValue,
        String articleKind,
        String blockType,
        BigDecimal soldAfter,
        BigDecimal deliveredAfter,
        StocktakeBucket bucket,
        List<StocktakeReason> reasons) {
}
