package com.intermanage.backend.nomenclature.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Marże wymagane do zapisania; pozycje spoza listy zostają bez zmian, pusta wartość czyści próg. */
public record NomenclatureMarginsRequest(@NotNull @Valid List<Item> margins) {

    public record Item(
            @NotNull @Pattern(regexp = "[0-9]{2}([0-9]{2}){0,2}", message = "Nieznana nomenklatura") String code,
            @DecimalMin(value = "0", message = "Marża musi być między 0 a 99,99%")
            @DecimalMax(value = "99.99", message = "Marża musi być między 0 a 99,99%")
            @Digits(integer = 2, fraction = 2, message = "Najwyżej dwa miejsca po przecinku")
            BigDecimal marginPercent) {
    }
}
