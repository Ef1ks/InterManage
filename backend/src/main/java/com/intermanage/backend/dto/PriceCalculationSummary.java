package com.intermanage.backend.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.intermanage.backend.entity.CalculationMode;

/**
 * Kalkulacja na liście: wyrób, koszt 1 kg z zapamiętanych cen składników i wyliczona cena sprzedaży (narzut + VAT,
 * zaokrąglona do ,99 zł). {@code missingPriceCount}: składniki bez ceny (koszt zaniżony).
 */
public record PriceCalculationSummary(
        UUID id,
        FinishedProductResponse product,
        String name,
        CalculationMode mode,
        BigDecimal batchYield,
        short vatRate,
        int itemCount,
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
        Instant updatedAt) {
}
