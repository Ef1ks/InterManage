package com.intermanage.backend.collector.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Promocje z kolektora. {@code startDate} pusty = od dziś. {@code macro}: zlecenie makra na komputerze sklepu, a
 * {@code macroError}: dlaczego nie ruszyło. {@code labelsQueued}: cenówki po udanym makrze czekają w kolejce plików.
 * {@code insertedAt}: ktoś wstawił je do Ustaw promocje.
 */
public record CollectorPromotionResponse(UUID id, String collectorName, LocalDateTime startedAt, Instant receivedAt,
        LocalDate startDate, LocalDate endDate, int lineCount, boolean macroRequested, Macro macro, String macroError,
        boolean labelsQueued, Instant insertedAt, String insertedBy) {

    /** Zlecenie makra ({@code status} jak w makrach agenta: QUEUED, OFFERED, RUNNING, DONE, FAILED…). */
    public record Macro(UUID id, String status, String message, Instant finishedAt) {
    }
}
