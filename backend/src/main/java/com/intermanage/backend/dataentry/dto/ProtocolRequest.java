package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Artykuły tabeli do pliku protokołu: EAN i ilość ze znakiem (0 jest pomijane). */
public record ProtocolRequest(
        @NotNull @Size(min = 1, max = 2000, message = "Od 1 do 2000 pozycji") List<@Valid Line> lines) {

    public record Line(
            @NotBlank @Pattern(regexp = "\\d{1,14}", message = "EAN może mieć tylko cyfry (do 14)") String ean,
            @NotNull @Digits(integer = 6, fraction = 3, message = "Ilość: najwyżej 6 cyfr i 3 miejsca po przecinku")
            BigDecimal quantity) {
    }
}
