package com.intermanage.backend.staff.dto;

import java.time.DayOfWeek;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ShiftRequest(
        @NotNull(message = "Wybierz dzień") DayOfWeek dayOfWeek,
        @NotNull(message = "Podaj początek")
        @Pattern(regexp = "^([01][0-9]|2[0-3]):[0-5][0-9]$", message = "Podaj godzinę jako GG:MM") String start,
        @NotNull(message = "Podaj koniec")
        @Pattern(regexp = "^([01][0-9]|2[0-3]):[0-5][0-9]$", message = "Podaj godzinę jako GG:MM") String end) {
}
