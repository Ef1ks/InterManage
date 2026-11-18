package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Artykuły w promocji z tabeli wprowadzania danych; {@code document}: dostawca i numer dokumentu do opisu. */
public record PromoPriceAddRequest(@Size(max = 300) String document,
        @NotEmpty @Size(max = 1000) List<@Valid Item> items) {

    public record Item(
            @NotBlank @Pattern(regexp = "\\d{1,14}", message = "EAN musi mieć od 1 do 14 cyfr") String ean,
            @NotBlank @Pattern(regexp = "\\d{1,13}", message = "ITM8 musi mieć same cyfry") String itm8,
            @NotNull @Size(max = 300) String name,
            @NotNull @DecimalMin(value = "0.01", message = "Cena musi być większa od zera")
            @Digits(integer = 9, fraction = 2) BigDecimal salePrice,
            @NotBlank @Pattern(regexp = "[126]", message = "Nieznany kod stawki VAT") String vatCode,
            @Digits(integer = 9, fraction = 2) BigDecimal promoPrice, LocalDate promoTo) {
    }
}
