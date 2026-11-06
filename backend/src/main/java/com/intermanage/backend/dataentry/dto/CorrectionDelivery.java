package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Przyjęcie PZ do wyboru przy fakturze korygującej. {@code matched}: przyjęcie faktury, którą ta korekta koryguje
 * (po numerze faktury albo przypiętej fakturze), reszta to wyniki wyszukiwania.
 */
public record CorrectionDelivery(UUID id, String number, LocalDateTime documentDate, String supplierName,
        String supplierDocumentNumber, BigDecimal purchaseValue, boolean matched) {
}
