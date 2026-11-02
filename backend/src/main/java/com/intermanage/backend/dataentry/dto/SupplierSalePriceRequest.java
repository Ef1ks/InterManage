package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/** Stała cena sprzedaży brutto artykułów dostawcy. */
public record SupplierSalePriceRequest(
        @NotNull(message = "Podaj cenę")
        @DecimalMin(value = "0.01", message = "Cena musi być większa od zera")
        @Digits(integer = 8, fraction = 2, message = "Najwyżej dwa miejsca po przecinku")
        BigDecimal salePrice) {
}
