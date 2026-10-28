package com.intermanage.backend.pricecheck.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/** Wgranie listy cadencier: ile artykułów i ile przybyło lub ubyło względem poprzedniej listy. */
public record CadencierImportResponse(
        UUID id,
        String fileName,
        LocalDateTime reportGeneratedAt,
        String importedBy,
        Instant importedAt,
        int totalRows,
        int addedCount,
        int removedCount) {
}
