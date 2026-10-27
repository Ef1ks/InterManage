package com.intermanage.backend.document.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Zamknięty raport przyjęć za okres. {@code pendingChanges}: przyjęcia z tego okresu zmienione po zamknięciu
 * (trafią do następnego raportu); {@code cancellable}: to ostatnie zamknięcie, więc da się je cofnąć.
 */
public record DeliveryReportClosingResponse(
        UUID id,
        LocalDate periodFrom,
        LocalDate periodTo,
        Instant closedAt,
        String closedBy,
        Instant cancelledAt,
        int pendingChanges,
        boolean cancellable) {
}
