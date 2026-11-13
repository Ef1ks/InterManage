package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Przyjęcie PZ do wyboru przy fakturze korygującej. {@code matched}: przyjęcie faktury, którą ta korekta koryguje
 * (po numerze faktury, przypiętej fakturze albo numerze WZ z XML faktury), reszta to wyniki wyszukiwania.
 * {@code commonArticles}: ile artykułów korekty jest w przyjęciu (tylko przy {@code matched}, inaczej 0); front sam
 * wybiera przyjęcie z największą liczbą.
 */
public record CorrectionDelivery(UUID id, String number, LocalDateTime documentDate, String supplierName,
        String supplierDocumentNumber, BigDecimal purchaseValue, boolean matched, int commonArticles) {
}
