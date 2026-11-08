package com.intermanage.backend.staff.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.intermanage.backend.staff.entity.TimeOffInitiative;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Czas wolny za nadgodziny pracownika danego dnia (art. 151² KP). Puste {@code minutes} usuwa wpis.
 * {@code departmentId} i {@code month}: grafik, który ma wrócić w odpowiedzi.
 */
public record ScheduleTimeOffRequest(@NotNull UUID departmentId,
        @NotNull @Pattern(regexp = "\\d{4}-\\d{2}", message = "Miesiąc w formacie RRRR-MM") String month,
        @NotNull UUID employeeId, @NotNull LocalDate date,
        @Min(value = 1, message = "Czas wolny co najmniej 1 minuta")
        @Max(value = 1440, message = "Czas wolny najwyżej 24 godziny") Integer minutes,
        TimeOffInitiative initiative) {
}
