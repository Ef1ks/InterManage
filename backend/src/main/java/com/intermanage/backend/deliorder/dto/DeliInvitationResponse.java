package com.intermanage.backend.deliorder.dto;

import java.time.Instant;
import java.util.UUID;

import com.intermanage.backend.deliorder.entity.InvitationStatus;
import com.intermanage.backend.store.dto.StoreSummary;

/** Zaproszenie do formularza: {@code formStore} zaprasza, {@code store} jest zaproszony. */
public record DeliInvitationResponse(
        UUID id,
        StoreSummary formStore,
        StoreSummary store,
        InvitationStatus status,
        Instant invitedAt,
        Instant respondedAt) {
}
