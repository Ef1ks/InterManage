package com.intermanage.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Dokument na liście: nagłówek i podsumowanie linii. Dostawca pusty, gdy linie mają różnych. {@code gratisCandidate},
 * gdy któraś linia to pozycja "GRATIS..."; {@code gratis}, gdy dokument zarejestrowano jako gratis.
 */
public record DocumentSummary(
        UUID id,
        String number,
        LocalDateTime documentDate,
        String supplierCode,
        String supplierName,
        String supplierDocumentNumber,
        int lineCount,
        BigDecimal purchaseValue,
        BigDecimal saleValueGross,
        boolean gratisCandidate,
        DocumentGratisResponse gratis,
        /** Faktury KSeF przyjęcia albo korekty od dostawcy: przypięte i dopasowane po numerze. */
        List<LinkedKsefInvoice> ksefInvoices,
        /** Przyjęcie PZ z przypiętymi korektami: ich liczba i wartości po korektach ({@code null}, gdy bez korekt). */
        CorrectionTotals correctionTotals,
        /** Korekta: przyjęcie, do którego ją przypięto. */
        LinkedDelivery correctionOf,
        /** Przyjęcie z fakturą: porównanie kwot netto z fakturami ({@code null} bez faktury). */
        InvoiceAmountCheck invoiceAmountCheck) {
}
