package com.intermanage.backend.document.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Propozycja automatycznego przypięcia faktury KSeF do przyjęcia PZ bez faktury: ten sam dostawca (NIP z listy
 * dostawców = NIP sprzedawcy), kwota netto faktury różni się od wartości zakupu o {@code difference} (bez znaku).
 */
public record AutoInvoiceMatch(
        UUID documentId,
        String documentNumber,
        LocalDateTime documentDate,
        String supplierName,
        String supplierDocumentNumber,
        BigDecimal purchaseValue,
        UUID invoiceId,
        String ksefNumber,
        String invoiceNumber,
        LocalDate issueDate,
        String sellerName,
        BigDecimal netAmount,
        BigDecimal difference) {
}
