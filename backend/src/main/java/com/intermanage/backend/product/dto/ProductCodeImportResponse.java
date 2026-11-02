package com.intermanage.backend.product.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/** Wgranie multikodów: nowe pary ITM8 + EAN, zmienione i pary, których zabrakło w pliku. */
public record ProductCodeImportResponse(
        UUID id,
        String fileName,
        LocalDateTime reportGeneratedAt,
        String importedBy,
        Instant importedAt,
        int totalRows,
        int createdCount,
        int updatedCount,
        int missingCount) {
}
