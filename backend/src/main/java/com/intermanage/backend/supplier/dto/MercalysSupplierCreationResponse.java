package com.intermanage.backend.supplier.dto;

import java.time.Instant;
import java.util.UUID;

/** Makro zakładania dostawcy w Mercalys skopiowane z aplikacji (kod zarezerwowany). */
public record MercalysSupplierCreationResponse(UUID id, String nip, String code, String name, Instant createdAt,
        String createdBy) {
}
