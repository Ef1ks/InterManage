package com.intermanage.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.intermanage.backend.entity.DocumentType;

/** Jedna linia dokumentu z raportu Wejścia/Wyjścia, już sprawdzona i przeliczona na typy z bazy. */
public record DocumentLineRow(
        DocumentType type,
        String documentNumber,
        LocalDateTime documentDate,
        String supplierDocumentNumber,
        int lineNumber,
        String supplierCode,
        String supplierName,
        String nomenclatureCode,
        String nomenclatureName,
        String itm8,
        String ean,
        String name,
        String brand,
        String unit,
        BigDecimal quantity,
        BigDecimal purchasePrice,
        BigDecimal salePriceCurrent,
        BigDecimal salePriceDocument,
        BigDecimal purchaseValue,
        BigDecimal saleValueGross,
        BigDecimal saleValueNet,
        Short vatRate,
        BigDecimal vatValue,
        BigDecimal marginValue,
        BigDecimal marginPercent) {

    /** Linię w dokumencie wyznacza numer razem z ITM8 (zwroty prasy mają wszystkie linie z numerem 1). */
    public String lineKey() {
        return lineNumber + "/" + itm8;
    }
}
