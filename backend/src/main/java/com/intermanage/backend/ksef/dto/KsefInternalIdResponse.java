package com.intermanage.backend.ksef.dto;

import java.time.Instant;
import java.util.UUID;

/** IDWew sklepu: faktury z nim w Podmiocie 3 trafiają do listy tego sklepu. */
public record KsefInternalIdResponse(UUID id, String internalId, String name, String createdBy, Instant createdAt) {
}
