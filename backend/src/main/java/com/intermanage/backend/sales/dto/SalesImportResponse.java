package com.intermanage.backend.sales.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Wgranie raportu sprzedaży: ile dni artykułów dopisano, ile już było w bazie (pominięte, zapisanej sprzedaży się nie
 * zmienia) i ile z nich ma w pliku inne wartości niż zapisane.
 */
public record SalesImportResponse(
        UUID id,
        String fileName,
        LocalDateTime reportGeneratedAt,
        LocalDate periodFrom,
        LocalDate periodTo,
        String importedBy,
        Instant importedAt,
        int totalRows,
        int createdCount,
        int existingCount,
        int conflictCount,
        /** Wiersze z niezakończonego dnia (raport z tego samego dnia); dopisze je raport z kolejnego dnia. */
        int incompleteCount) {
}
