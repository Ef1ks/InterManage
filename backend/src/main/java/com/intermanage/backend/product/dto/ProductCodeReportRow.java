package com.intermanage.backend.product.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Jedna para ITM8 + EAN z multikodów; puste pola, gdy raport ich nie podał. */
public record ProductCodeReportRow(
        String itm8,
        String ean,
        String name,
        boolean prio,
        String saleUnit,
        BigDecimal salePack,
        BigDecimal priceFactor,
        BigDecimal salePrice,
        LocalDate removalDate,
        Short depositCode) {
}
