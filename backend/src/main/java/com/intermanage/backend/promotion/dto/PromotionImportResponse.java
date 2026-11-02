package com.intermanage.backend.promotion.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** Wgranie raportu promocji: nowe, zmienione i promocje z zakresu raportu, których zabrakło w pliku. */
public record PromotionImportResponse(
        UUID id,
        String fileName,
        LocalDateTime reportGeneratedAt,
        LocalDate endFrom,
        LocalDate endTo,
        String importedBy,
        Instant importedAt,
        int totalRows,
        int createdCount,
        int updatedCount,
        int missingCount) {
}
