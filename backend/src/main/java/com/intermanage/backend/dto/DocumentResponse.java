package com.intermanage.backend.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Dokument ze wszystkimi liniami; {@code typeSingular} to pełna nazwa do nagłówka ("Likwidacja"). */
public record DocumentResponse(
        UUID id,
        String type,
        String typeLabel,
        String typeSingular,
        String number,
        LocalDateTime documentDate,
        String supplierCode,
        String supplierName,
        String supplierDocumentNumber,
        int lineCount,
        BigDecimal purchaseValue,
        BigDecimal saleValueGross,
        /** Typ dokumentu można rejestrować jako gratis (przeniesienia stanów). */
        boolean gratisAllowed,
        boolean gratisCandidate,
        DocumentGratisResponse gratis,
        /** Ostatnia zmiana nagłówka albo linii przez import. */
        Instant updatedAt,
        List<DocumentLineResponse> lines,
        /** Zamówienia garmażu jadące na tym dokumencie (tylko przy ruchach międzymagazynowych). */
        List<LinkedDeliOrder> deliOrders) {

    public record DocumentLineResponse(
            UUID id,
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
    }
}
