package com.intermanage.backend.stocktake.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/** Wgrany raport: nowe i zmienione artykuły oraz te, których różnica znikła (tylko przy raporcie całego sklepu). */
public record StocktakeImportResponse(
        UUID id,
        UUID stocktakeId,
        String fileName,
        String selection,
        boolean wholeStore,
        LocalDateTime reportGeneratedAt,
        int lineCount,
        BigDecimal differenceValue,
        int createdCount,
        int updatedCount,
        int resolvedCount,
        String importedBy,
        Instant importedAt) {
}
