package com.intermanage.backend.document.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Faktura KSeF do przypięcia dokumentu. {@code difference}: o ile kwota netto faktury różni się od wartości dokumentu
 * (bez znaku - korekty bywają zapisane z minusem po jednej stronie), {@code sameSupplier}: NIP sprzedawcy jest NIP-em
 * dostawcy dokumentu, {@code documents}: numery dokumentów sklepu, do których faktura jest już przypięta.
 */
public record KsefInvoiceCandidate(
        UUID id,
        String ksefNumber,
        String invoiceNumber,
        LocalDate issueDate,
        String sellerName,
        String sellerNip,
        BigDecimal netAmount,
        BigDecimal grossAmount,
        boolean correction,
        boolean sameSupplier,
        BigDecimal difference,
        List<String> documents) {
}
