package com.intermanage.backend.dto;

import java.time.Instant;
import java.util.UUID;

import com.intermanage.backend.entity.InvitationStatus;

/** Zaproszenie do formularza: {@code formStore} zaprasza, {@code store} jest zaproszony. */
public record DeliInvitationResponse(
        UUID id,
        StoreSummary formStore,
        StoreSummary store,
        InvitationStatus status,
        Instant invitedAt,
        Instant respondedAt) {
}
