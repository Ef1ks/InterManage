package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Budżety rejonów do zapisania; rejony spoza listy zostają bez zmian, pusta wartość czyści budżet. */
public record RegionBudgetsRequest(@NotNull @Valid List<Item> budgets) {

    public record Item(
            @NotNull @Pattern(regexp = "[0-9]{2}", message = "Nieznany rejon") String regionCode,
            @DecimalMin(value = "-100", message = "Marża musi być między -100 a 100%")
            @DecimalMax(value = "100", message = "Marża musi być między -100 a 100%")
            @Digits(integer = 3, fraction = 2, message = "Najwyżej dwa miejsca po przecinku")
            BigDecimal marginPercent,
            @DecimalMin(value = "0", message = "Straty muszą być między 0 a 100%")
            @DecimalMax(value = "100", message = "Straty muszą być między 0 a 100%")
            @Digits(integer = 3, fraction = 2, message = "Najwyżej dwa miejsca po przecinku")
            BigDecimal maxLossPercent) {
    }
}
