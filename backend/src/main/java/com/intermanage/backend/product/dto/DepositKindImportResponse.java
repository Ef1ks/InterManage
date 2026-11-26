package com.intermanage.backend.product.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/** Wgranie listy opakowań zwrotnych; {@code importedBy} pusty, gdy konto osoby usunięto. */
public record DepositKindImportResponse(UUID id, String fileName, LocalDateTime reportGeneratedAt, String importedBy,
        Instant importedAt, int totalRows, int createdCount, int updatedCount, int missingCount) {
}
