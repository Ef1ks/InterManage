package com.intermanage.backend.staff.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Zastępuje układających, obsadę i przerwy. Pusta obsada stanowiska = bez minimum albo bez celu, {@code breaks: null}
 * = przerwy bez zmian, {@code toleranceMinutes: null} = tolerancja bez zmian, {@code payEnabled: null}
 * = liczenie wynagrodzeń bez zmian.
 */
public record ScheduleSettingsRequest(@NotNull List<UUID> managerIds, @NotNull List<@Valid Minimum> minimums,
        List<@Valid Break> breaks,
        @Min(value = 0, message = "Tolerancja od 0 minut") @Max(value = 60, message = "Tolerancja do 60 minut")
        Integer toleranceMinutes, Boolean payEnabled) {

    public record Minimum(@NotNull UUID positionId,
            @Min(value = 1, message = "Obsada od 1 osoby") @Max(value = 99, message = "Obsada do 99 osób") Integer minCount,
            @Min(value = 1, message = "Obsada od 1 osoby") @Max(value = 99, message = "Obsada do 99 osób") Integer targetCount) {
    }

    public record Break(
            @NotNull(message = "Podaj początek przerwy")
            @Pattern(regexp = "^([01][0-9]|2[0-3]):[0-5][0-9]$", message = "Podaj godzinę jako GG:MM") String start,
            @NotNull(message = "Podaj koniec przerwy")
            @Pattern(regexp = "^([01][0-9]|2[0-3]):[0-5][0-9]$", message = "Podaj godzinę jako GG:MM") String end,
            boolean paid) {
    }
}
