package com.intermanage.backend.deliproduction.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/** Karta produkcji z formularza: data i pozycje w kolejności na karcie. */
public record ProductionCardRequest(
        @NotNull(message = "Wybierz datę produkcji") LocalDate productionDate,
        @NotEmpty(message = "Karta nie ma pozycji") List<@Valid Line> lines) {

    public record Line(
            @NotNull(message = "Wybierz artykuł") UUID articleId,
            @NotNull(message = "Wpisz ilość") @DecimalMin(value = "0.001", message = "Ilość musi być większa od zera")
            @Digits(integer = 9, fraction = 3, message = "Nieprawidłowa ilość") BigDecimal quantity,
            @NotNull(message = "Wpisz godzinę") @Min(value = 0, message = "Godzina od 0")
            @Max(value = 22, message = "Godzina najwyżej 22") Integer startHour) {
    }
}
