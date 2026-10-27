package com.intermanage.backend.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** Przyjęcie PZ, do którego przypięto korektę. */
public record LinkedDelivery(UUID id, String number, LocalDateTime documentDate, String supplierName) {
}
