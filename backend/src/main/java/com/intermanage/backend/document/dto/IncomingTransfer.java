package com.intermanage.backend.document.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.store.dto.StoreSummary;

/**
 * Ruch międzymagazynowy innego sklepu wystawiony dla tego sklepu, do wprowadzenia u siebie. {@code id} to id wpisu,
 * nie dokumentu (dokument należy do sklepu wysyłającego). Linie tylko w szczegółach, na liście pusta lista.
 */
public record IncomingTransfer(
        UUID id,
        StoreSummary fromStore,
        String number,
        LocalDateTime documentDate,
        int lineCount,
        BigDecimal purchaseValue,
        BigDecimal saleValueGross,
        String createdBy,
        Instant createdAt,
        String enteredBy,
        Instant enteredAt,
        List<DocumentResponse.DocumentLineResponse> lines) {
}
