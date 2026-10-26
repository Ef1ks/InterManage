package com.intermanage.backend.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Podgląd "Odśwież ceny" dla jednej kalkulacji: cena teraz ({@code currentPrice}, własna albo wyliczona), wyliczona
 * przed odświeżeniem i po nim. {@code needsDecision}, gdy trzeba zapytać: kalkulacja ma własną cenę, a wyliczona się
 * zmieniła, albo cena wyliczona zmienia się o {@code changePercent} >= 10%.
 */
public record PriceChangeResponse(
        UUID calculationId,
        String name,
        String ean,
        BigDecimal currentPrice,
        BigDecimal manualPrice,
        BigDecimal previousCalculatedPrice,
        BigDecimal newCalculatedPrice,
        BigDecimal changePercent,
        boolean needsDecision) {
}
