package com.intermanage.backend.pricecheck.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;

/** Minimalna marża (% od sprzedaży netto); pusta wyłącza zgłaszanie. */
public record PriceCheckSettingsRequest(
        @DecimalMin(value = "-100", message = "Marża musi być między -100 a 100%")
        @DecimalMax(value = "100", message = "Marża musi być między -100 a 100%")
        @Digits(integer = 3, fraction = 2, message = "Najwyżej dwa miejsca po przecinku")
        BigDecimal minMarginPercent) {
}
