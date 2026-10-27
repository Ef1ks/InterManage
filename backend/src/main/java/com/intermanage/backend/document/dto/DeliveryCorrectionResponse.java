package com.intermanage.backend.document.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/** Korekta przypięta do przyjęcia PZ: dokument korekty (błędu sklepu albo od dostawcy) i kto ją przypiął. */
public record DeliveryCorrectionResponse(
        UUID id,
        String type,
        String typeSingular,
        String number,
        LocalDateTime documentDate,
        int lineCount,
        BigDecimal purchaseValue,
        BigDecimal saleValueGross,
        Instant linkedAt,
        String linkedBy) {
}
