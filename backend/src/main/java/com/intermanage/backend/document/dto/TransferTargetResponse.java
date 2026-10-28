package com.intermanage.backend.document.dto;

import java.time.Instant;

import com.intermanage.backend.store.dto.StoreSummary;

/** Sklep, dla którego wystawiono ruch międzymagazynowy; {@code enteredAt}, gdy sklep wprowadził go u siebie. */
public record TransferTargetResponse(
        StoreSummary store,
        String createdBy,
        Instant createdAt,
        String enteredBy,
        Instant enteredAt) {
}
