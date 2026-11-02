package com.intermanage.backend.deliproduction.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Karta na liście (także przy dokumencie MM): sklep, data, liczba pozycji i suma ilości. */
public record ProductionCardSummary(
        UUID id,
        UUID storeId,
        String storeNumber,
        String storeName,
        LocalDate productionDate,
        long lineCount,
        BigDecimal totalQuantity,
        String createdBy,
        Instant createdAt) {
}
