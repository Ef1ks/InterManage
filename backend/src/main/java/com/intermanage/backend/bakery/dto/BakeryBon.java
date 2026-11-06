package com.intermanage.backend.bakery.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Przyjęcie PZ dostawcy: {@code correctedValue} to zakup po korektach ({@code null} bez korekt),
 * {@code invoices} numery jego faktur, {@code checkId} weryfikacja, w której już jest.
 */
public record BakeryBon(UUID id, String number, String bonNumber, LocalDateTime documentDate,
        String supplierDocumentNumber, BigDecimal purchaseValue, BigDecimal correctedValue, int correctionCount,
        List<String> invoices, UUID checkId) {
}
