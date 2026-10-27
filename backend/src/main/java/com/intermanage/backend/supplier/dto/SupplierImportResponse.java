package com.intermanage.backend.supplier.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/** Wgranie listy dostawców: ilu nowych, ilu ze zmienionymi danymi i ilu znanych zabrakło w pliku (zostają w bazie). */
public record SupplierImportResponse(
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
