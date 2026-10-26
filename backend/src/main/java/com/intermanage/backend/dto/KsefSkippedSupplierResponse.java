package com.intermanage.backend.dto;

import java.time.Instant;
import java.util.UUID;

/** Stały dostawca pomijany na liście faktur KSeF sklepu. */
public record KsefSkippedSupplierResponse(UUID id, String nip, String name, String createdBy, Instant createdAt) {
}
