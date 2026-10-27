package com.intermanage.backend.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/** Wgranie listy nomenklatur: ile pozycji nowych, ile ze zmienionymi danymi i ile znanych zabrakło w pliku (zostają). */
public record NomenclatureImportResponse(
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
