package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Budżet rejonu w ustawieniach sklepu. {@code group} to grupa z podsumowania (np. "Świeże tradycyjne"), żeby
 * ustawienia układały się jak raport. Rejon bez ustawionego budżetu ma puste wartości.
 */
public record RegionBudgetResponse(
        String regionCode,
        String name,
        String group,
        BigDecimal marginPercent,
        BigDecimal maxLossPercent,
        String updatedBy,
        Instant updatedAt) {
}
