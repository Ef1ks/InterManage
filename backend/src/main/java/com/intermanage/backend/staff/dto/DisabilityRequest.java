package com.intermanage.backend.staff.dto;

import java.time.LocalDate;

import com.intermanage.backend.staff.entity.DisabilityDegree;

import jakarta.validation.constraints.NotNull;

public record DisabilityRequest(
        @NotNull(message = "Wybierz stopień") DisabilityDegree degree,
        @NotNull(message = "Podaj datę zaliczenia do stopnia") LocalDate startDate,
        LocalDate endDate,
        boolean doctorConsent) {
}
