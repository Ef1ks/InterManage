package com.intermanage.backend.deliprice.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.deliprice.entity.CalculationMode;

/**
 * Kalkulacja ze składnikami i ich zapamiętanymi cenami. {@code markupPercent} to marża doliczana do kosztu, z którą
 * liczy się {@code salePrice}: {@code targetMarkup} ustawiona na wyrobie, a bez niej standardowa.
 */
public record PriceCalculationResponse(
        UUID id,
        FinishedProductResponse product,
        String name,
        CalculationMode mode,
        BigDecimal batchYield,
        short vatRate,
        BigDecimal markupPercent,
        BigDecimal targetMarkup,
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
