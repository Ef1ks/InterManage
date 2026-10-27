package com.intermanage.backend.product.dto;

import java.math.BigDecimal;

/** Jeden artykuł z pliku, pola w jednostkach i dokładności kolumn tabeli {@code products}. */
public record ProductReportRow(
        String itm8,
        String ean,
        String name,
        String brand,
        String supplier,
        String nomenclature,
        BigDecimal salePrice,
        BigDecimal purchasePrice,
        BigDecimal stock,
        BigDecimal rotation,
        BigDecimal caseSize,
        Boolean active,
        Boolean priorityEan,
        Short vatCode,
        String unit,
        BigDecimal netContent,
        Integer dlv,
        Short rp,
        Short depositCode,
        String reference,
        Short tillCode,
        Short typeCode,
        String kind,
        Short saleUnitCode,
        String cnCode) {
}
