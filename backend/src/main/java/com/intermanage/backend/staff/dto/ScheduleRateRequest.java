package com.intermanage.backend.staff.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Stawka za godzinę pracownika od miesiąca {@code month} (umowa o pracę albo zlecenie). Pusta {@code rate} usuwa stawkę
 * od tego miesiąca. {@code departmentId} i {@code month}: grafik, który ma wrócić w odpowiedzi.
 */
public record ScheduleRateRequest(@NotNull UUID departmentId,
        @NotNull @Pattern(regexp = "\\d{4}-\\d{2}", message = "Miesiąc w formacie RRRR-MM") String month,
        @NotNull UUID employeeId, boolean civil,
        @DecimalMin(value = "0.01", message = "Stawka musi być większa od zera")
        @DecimalMax(value = "9999.99", message = "Stawka do 9999,99 zł")
        @Digits(integer = 4, fraction = 2, message = "Stawka z dokładnością do grosza") BigDecimal rate) {
}
