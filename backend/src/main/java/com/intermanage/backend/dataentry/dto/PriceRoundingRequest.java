package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Cała lista przedziałów zaokrąglania sklepu; pusta lista = bez zaokrąglania. */
public record PriceRoundingRequest(
        @NotNull @Size(max = 30, message = "Najwyżej 30 przedziałów") List<@Valid Rule> rules) {

    public record Rule(
            @NotNull(message = "Podaj początek przedziału")
            @DecimalMin(value = "0", message = "Cena nie może być ujemna")
            @Digits(integer = 8, fraction = 2, message = "Najwyżej dwa miejsca po przecinku")
            BigDecimal priceFrom,
            @Digits(integer = 8, fraction = 2, message = "Najwyżej dwa miejsca po przecinku")
            BigDecimal priceTo,
            @NotNull(message = "Podaj końcówkę")
            @DecimalMin(value = "0.01", message = "Końcówka od 0,01 do 0,99 zł")
            @DecimalMax(value = "0.99", message = "Końcówka od 0,01 do 0,99 zł")
            @Digits(integer = 1, fraction = 2, message = "Najwyżej dwa miejsca po przecinku")
            BigDecimal ending) {
    }
}
