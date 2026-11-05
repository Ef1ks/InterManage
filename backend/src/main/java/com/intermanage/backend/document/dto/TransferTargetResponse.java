package com.intermanage.backend.document.dto;

import java.time.Instant;

import com.intermanage.backend.store.dto.StoreSummary;

/**
 * Sklep, dla którego wystawiono ruch międzymagazynowy; {@code enteredAt}, gdy sklep oznaczył go jako wprowadzony,
 * {@code receipt}, gdy przypiął swój dokument przyjęcia (do tego czasu dokument czeka).
 */
public record TransferTargetResponse(
        StoreSummary store,
        String createdBy,
        Instant createdAt,
        String enteredBy,
        Instant enteredAt,
        TransferReceiptResponse receipt) {
}
