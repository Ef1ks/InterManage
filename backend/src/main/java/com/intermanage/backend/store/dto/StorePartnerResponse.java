package com.intermanage.backend.store.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Znajomość albo zaproszenie widziane od strony sklepu: {@code store} to drugi sklep, {@code sent} mówi, czy to ten
 * sklep zapraszał. {@code acceptedAt} pusty = zaproszenie czeka na odpowiedź.
 */
public record StorePartnerResponse(
        UUID id,
        StoreSummary store,
        boolean sent,
        String invitedBy,
        Instant invitedAt,
        String acceptedBy,
        Instant acceptedAt) {
}
