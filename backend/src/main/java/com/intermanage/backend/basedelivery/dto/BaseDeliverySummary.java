package com.intermanage.backend.basedelivery.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Wgrany dokument dostawy z bazy na liście do otwarcia. */
public record BaseDeliverySummary(
        UUID id,
        String number,
        LocalDate deliveryDate,
        String fileName,
        int lineCount,
        Instant uploadedAt,
        String uploadedBy) {
}
