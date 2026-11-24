package com.intermanage.backend.product.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Import kartoteki w tle: {@code result} jest dopiero po zakończeniu ({@code DONE}). {@code percent} (0-100) liczy
 * cały import: odczyt pliku do 30%, zapis paczkami do 95%, reszta to zakończenie. {@code processedRows} i
 * {@code totalRows} są znane od etapu zapisu.
 */
public record ProductImportJobResponse(UUID id, String fileName, Status status, Phase phase, int percent,
        int processedRows, int totalRows, Instant startedAt, ProductImportResponse result) {

    public enum Status {
        RUNNING, DONE, FAILED
    }

    public enum Phase {
        /** Czeka, aż skończą się importy innych sklepów. */
        WAITING,
        /** Czytanie i sprawdzanie pliku. */
        READING,
        /** Scalanie z kartoteką paczkami. */
        SAVING,
        /** Artykuły spoza pliku, szkice, połączenia artykułów. */
        FINISHING
    }
}
