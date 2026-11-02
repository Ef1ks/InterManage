package com.intermanage.backend.meat.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Dokument WZ na liście. */
public record MeatDeliverySummary(UUID id, LocalDate deliveryDate, MeatSupplier supplier, String documentNumber,
        int lineCount, boolean hasScan, String createdBy, Instant createdAt) {
}
