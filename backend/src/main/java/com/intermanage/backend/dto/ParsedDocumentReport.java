package com.intermanage.backend.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Raport Wejścia/Wyjścia po odczytaniu: nagłówek, linie obsługiwanych typów dokumentów i liczba linii typów,
 * których nie zapisujemy (np. MM), według ich kodu.
 */
public record ParsedDocumentReport(
        String storeNumber,
        LocalDateTime generatedAt,
        LocalDate periodFrom,
        LocalDate periodTo,
        int totalLines,
        List<DocumentLineRow> lines,
        Map<String, Integer> skippedLinesByType) {

    public int skippedLines() {
        return skippedLinesByType.values().stream().mapToInt(Integer::intValue).sum();
    }
}
