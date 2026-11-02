package com.intermanage.backend.basedelivery.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Dokument dostawy z bazy z nośnikami (liczba linii i wartość netto) i wszystkimi liniami. */
public record BaseDeliveryResponse(
        UUID id,
        String number,
        LocalDate deliveryDate,
        String fileName,
        Instant uploadedAt,
        String uploadedBy,
        List<Carrier> carriers,
        List<BaseDeliveryLineResponse> lines) {

    public record Carrier(String number, int lineCount, BigDecimal netValue) {
    }
}
