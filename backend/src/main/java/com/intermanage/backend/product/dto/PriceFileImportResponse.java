package com.intermanage.backend.product.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Wczytany plik TRACEART: artykuły w pliku, z tego ze zmienioną ceną albo promocją, pominięte, bo kartoteka ma
 * nowszą cenę, i spoza kartoteki.
 */
public record PriceFileImportResponse(
        UUID id,
        String fileName,
        LocalDateTime fileTime,
        String importedBy,
        Instant importedAt,
        int totalLines,
        int articleCount,
        int updatedCount,
        int olderCount,
        int unknownCount) {
}
