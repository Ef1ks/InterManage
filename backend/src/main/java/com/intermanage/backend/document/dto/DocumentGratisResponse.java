package com.intermanage.backend.dto;

import java.time.Instant;
import java.util.UUID;

/** Rejestracja dokumentu jako gratisu; wartość to sprzedaż brutto dokumentu. */
public record DocumentGratisResponse(
        UUID supplierId,
        String supplierCode,
        String supplierName,
        String registeredBy,
        Instant registeredAt) {
}
