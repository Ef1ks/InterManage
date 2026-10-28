package com.intermanage.backend.pricecheck.dto;

import java.time.Instant;
import java.util.UUID;

import com.intermanage.backend.pricecheck.entity.PriceCheckStatus;

/** Zmiana statusu wyjaśniania przyjęcia; {@code createdBy} pusty, gdy konto zostało usunięte. */
public record PriceCheckReviewResponse(UUID id, PriceCheckStatus status, String note, String createdBy,
        Instant createdAt) {
}
