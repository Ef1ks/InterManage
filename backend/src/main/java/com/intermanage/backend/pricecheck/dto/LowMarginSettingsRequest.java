package com.intermanage.backend.pricecheck.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/** Progi raportu marż poza normą w punktach procentowych od marży wymaganej. */
public record LowMarginSettingsRequest(
        @NotNull(message = "Podaj próg za niskiej marży")
        @DecimalMin(value = "0", message = "Próg musi być między 0 a 100 punktów")
        @DecimalMax(value = "100", message = "Próg musi być między 0 a 100 punktów")
        @Digits(integer = 3, fraction = 2, message = "Najwyżej dwa miejsca po przecinku")
        BigDecimal lowGap,
        @NotNull(message = "Podaj próg za wysokiej marży")
        @DecimalMin(value = "0", message = "Próg musi być między 0 a 100 punktów")
        @DecimalMax(value = "100", message = "Próg musi być między 0 a 100 punktów")
        @Digits(integer = 3, fraction = 2, message = "Najwyżej dwa miejsca po przecinku")
        BigDecimal highGap) {
}
