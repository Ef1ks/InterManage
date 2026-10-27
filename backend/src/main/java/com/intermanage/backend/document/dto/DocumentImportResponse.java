package com.intermanage.backend.document.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** Wgranie raportu Wejścia/Wyjścia: ile było linii, ile dokumentów nowych i uzupełnionych, ile linii nieobsługiwanych typów pominięto. */
public record DocumentImportResponse(
        UUID id,
        String fileName,
        LocalDateTime reportGeneratedAt,
        LocalDate periodFrom,
        LocalDate periodTo,
        String importedBy,
        Instant importedAt,
        int totalLines,
        int savedLines,
        int createdCount,
        int updatedCount,
        int skippedLines,
        String skippedTypes) {
}
