package com.intermanage.backend.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.entity.CalculationMode;

/**
 * Kalkulacja ze składnikami i ich zapamiętanymi cenami. {@code markupPercent} to narzut na koszt, z którym
 * liczy się {@code salePrice} (frontend przelicza z nim na bieżąco w kreatorze).
 */
public record PriceCalculationResponse(
        UUID id,
        FinishedProductResponse product,
        String name,
        CalculationMode mode,
        BigDecimal batchYield,
        short vatRate,
        int markupPercent,
        BigDecimal totalCost,
        BigDecimal costPerKg,
        /** Cena do eksportu: własna, a bez niej wyliczona. */
        BigDecimal salePrice,
        BigDecimal calculatedPrice,
        BigDecimal manualPrice,
        String manualPriceSetBy,
        Instant manualPriceSetAt,
        int missingPriceCount,
        Instant pricesRefreshedAt,
        String updatedBy,
        Instant updatedAt,
        List<PriceProductResponse> items) {
}
