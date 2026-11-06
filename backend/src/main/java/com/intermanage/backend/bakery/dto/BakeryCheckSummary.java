package com.intermanage.backend.bakery.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Weryfikacja na liście: bony, faktury i zakres dat WZ; {@code filledWz} = WZ z wpisanymi ilościami. */
public record BakeryCheckSummary(UUID id, Instant createdAt, String createdBy, List<String> bons,
        List<String> invoices, int wzCount, int filledWz, LocalDate from, LocalDate to) {
}
