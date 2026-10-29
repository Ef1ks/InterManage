package com.intermanage.backend.pricecheck.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Wgranie pliku incydentów: dzień incydentu (z nazwy pliku), ile artykułów i ile z nich nowych dla tego dnia. */
public record IncidentImportResponse(
        UUID id,
        String fileName,
        LocalDate incidentDate,
        String importedBy,
        Instant importedAt,
        int totalRows,
        int addedCount) {
}
