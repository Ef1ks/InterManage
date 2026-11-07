package com.intermanage.backend.stocktake.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Inwentura na liście. */
public record StocktakeSummary(
        UUID id,
        String number,
        String name,
        LocalDate stocktakeDate,
        long lineCount,
        BigDecimal netValue,
        Instant lastImportAt) {
}
