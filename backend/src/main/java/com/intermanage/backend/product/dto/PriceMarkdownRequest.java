package com.intermanage.backend.product.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PriceMarkdownRequest(
        @NotBlank String itm8,
        @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal newPrice,
        @NotNull LocalDate validFrom,
        @NotNull LocalDate validTo) {
}
