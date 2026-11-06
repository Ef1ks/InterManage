package com.intermanage.backend.deliprice.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.intermanage.backend.deliprice.entity.CalculationMode;

/**
 * Kalkulacja na liście: wyrób, koszt 1 kg z zapamiętanych cen składników i wyliczona cena sprzedaży (marża + VAT,
 * zaokrąglona do ,99 zł). {@code missingPriceCount}: składniki bez ceny (koszt zaniżony). {@code itemCount} 0 to
 * kalkulacja bez przepisu (z automatycznego importu): czeka na składniki, a {@code salePrice} to cena z półki.
 */
public record PriceCalculationSummary(
        UUID id,
        FinishedProductResponse product,
        String name,
        CalculationMode mode,
        BigDecimal batchYield,
        short vatRate,
        BigDecimal markupPercent,
        BigDecimal targetMarkup,
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
