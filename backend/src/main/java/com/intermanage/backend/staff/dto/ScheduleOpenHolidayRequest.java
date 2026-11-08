package com.intermanage.backend.staff.dto;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Czy sklep jest czynny w dane święto. {@code departmentId} i {@code month}: grafik, który ma wrócić w odpowiedzi. */
public record ScheduleOpenHolidayRequest(@NotNull UUID departmentId,
        @NotNull @Pattern(regexp = "\\d{4}-\\d{2}", message = "Miesiąc w formacie RRRR-MM") String month,
        @NotNull LocalDate date, boolean open) {
}
