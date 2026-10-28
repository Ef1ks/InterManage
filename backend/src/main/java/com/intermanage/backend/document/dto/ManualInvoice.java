package com.intermanage.backend.document.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Faktura spoza KSeF przy dokumencie: dostawca z listy dostawców sklepu, numer i (opcjonalnie) data wystawienia;
 * {@code file}: dołączony plik (PDF albo zdjęcie) albo {@code null}.
 */
public record ManualInvoice(
        UUID id,
        String invoiceNumber,
        LocalDate issueDate,
        UUID supplierId,
        String supplierName,
        Instant createdAt,
        String createdBy,
        ManualInvoiceFile file) {
}
