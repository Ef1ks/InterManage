package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Cała stała marża dostawcy naraz: domyślna (pusta = bez domyślnej) i wyjątki na kodach nomenklatury. Brak domyślnej
 * i wyjątków usuwa marżę dostawcy.
 */
public record SupplierMarginRequest(
        @DecimalMin(value = "0", message = "Marża nie może być ujemna")
        @DecimalMax(value = "99.99", message = "Marża musi być mniejsza niż 100%")
        @Digits(integer = 2, fraction = 2, message = "Najwyżej dwa miejsca po przecinku")
        BigDecimal defaultPercent,
        @NotNull @Size(max = 500, message = "Najwyżej 500 wyjątków") List<@Valid Rule> rules) {

    public record Rule(
            @NotNull(message = "Wybierz nomenklaturę")
            @Pattern(regexp = "\\d{2}|\\d{4}|\\d{6}", message = "Kod rejonu, rodziny albo podrodziny")
            String code,
            @NotNull(message = "Podaj marżę")
            @DecimalMin(value = "0", message = "Marża nie może być ujemna")
            @DecimalMax(value = "99.99", message = "Marża musi być mniejsza niż 100%")
            @Digits(integer = 2, fraction = 2, message = "Najwyżej dwa miejsca po przecinku")
            BigDecimal marginPercent) {
    }
}
