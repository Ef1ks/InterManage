package com.intermanage.backend.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Wiersz listy faktur KSeF. {@code entered}: numer faktury jest w przyjęciu PZ ({@code deliveries}) albo ktoś ją
 * oznaczył ręcznie ({@code enteredBy}, {@code enteredAt}). {@code internalIds} to IDWew z Podmiotu 3.
 */
public record KsefInvoiceSummary(
        UUID id,
        String ksefNumber,
        String invoiceNumber,
        LocalDate issueDate,
        Instant invoicingDate,
        String sellerNip,
        String sellerName,
        BigDecimal netAmount,
        BigDecimal grossAmount,
        BigDecimal vatAmount,
        String currency,
        String invoiceType,
        boolean correction,
        boolean hasAttachment,
        List<String> internalIds,
        boolean entered,
        String enteredBy,
        Instant enteredAt,
        List<Delivery> deliveries,
        boolean supplierSkipped) {

    /** Przyjęcie PZ z numerem tej faktury. */
    public record Delivery(UUID id, String number) {
    }
}
