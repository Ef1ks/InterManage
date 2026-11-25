package com.intermanage.backend.collector.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Kolektor w Ustawieniach sklepu. {@code pairCode} jest tylko w odpowiedzi, która go utworzyła (w bazie jest skrót);
 * {@code catalogCurrent}: kolektor ma dane z ostatnich importów sklepu,
 * {@code newVersion}: nowsza wersja programu, którą pobierze na podstawce (brak = ma najnowszą).
 */
public record StoreCollectorResponse(UUID id, String name, Instant createdAt, String createdBy, Instant pairedAt,
        Instant pairExpiresAt, String pairCode, Instant lastSeenAt, String lastVersion, String deviceName,
        Instant catalogSyncedAt, boolean catalogCurrent, String newVersion, Instant deletedAt) {
}
