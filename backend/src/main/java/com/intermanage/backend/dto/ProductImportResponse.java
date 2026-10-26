package com.intermanage.backend.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

public record ProductImportResponse(
        UUID id,
        String fileName,
        LocalDateTime reportGeneratedAt,
        String importedBy,
        Instant importedAt,
        int totalRows,
        int createdCount,
        int updatedCount,
        int unchangedCount,
        int missingCount,
        int eanChangedCount,
        int eanMovedCount) {
}
