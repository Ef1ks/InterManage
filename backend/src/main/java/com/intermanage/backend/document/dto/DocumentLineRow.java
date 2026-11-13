package com.intermanage.backend.document.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.intermanage.backend.document.entity.DocumentType;

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

    /**
     * Ta sama linia różniąca się tylko nazwą dostawcy: gdy kod dostawcy ma w systemie sklepu dwie nazwy, raport
     * podwaja wiersz (PPP 240490000005 z 12.10.2024, kod 90: NORIMPEX i LACOSTA).
     */
    public boolean sameExceptSupplierName(DocumentLineRow other) {
        return equals(new DocumentLineRow(other.type, other.documentNumber, other.documentDate,
                other.supplierDocumentNumber, other.lineNumber, other.supplierCode, supplierName,
                other.nomenclatureCode, other.nomenclatureName, other.itm8, other.ean, other.name, other.brand,
                other.unit, other.quantity, other.purchasePrice, other.salePriceCurrent, other.salePriceDocument,
                other.purchaseValue, other.saleValueGross, other.saleValueNet, other.vatRate, other.vatValue,
                other.marginValue, other.marginPercent));
    }
}
