package com.intermanage.backend.document.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** Przyjęcie PZ albo ruch międzymagazynowy, do którego przypięto korektę. */
public record LinkedDelivery(UUID id, String number, LocalDateTime documentDate, String supplierName) {
}
