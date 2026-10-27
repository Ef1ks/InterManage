package com.intermanage.backend.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Wiersz listy faktur KSeF. {@code entered}: faktura ma dokument sklepu ({@code deliveries}: przyjęcie PZ z jej
 * numerem albo dokument przypięty ręcznie) albo ktoś ją oznaczył ręcznie ({@code enteredBy}, {@code enteredAt}). {@code internalIds} to IDWew z Podmiotu 3.
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

    /**
     * Dokument sklepu tej faktury: przyjęcie PZ z jej numerem ({@code linked} = false) albo przyjęcie lub korekta od
     * dostawcy przypięte ręcznie.
     */
    public record Delivery(UUID id, String type, String typeSingular, String number, boolean linked) {
    }
}
