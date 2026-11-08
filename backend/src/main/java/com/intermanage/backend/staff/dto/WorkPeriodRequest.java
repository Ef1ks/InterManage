package com.intermanage.backend.staff.dto;

import java.time.LocalDate;

import com.intermanage.backend.staff.entity.WorkPeriodKind;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record WorkPeriodRequest(
        @NotNull(message = "Wybierz rodzaj okresu") WorkPeriodKind kind,
        @Size(max = 150, message = "Maksymalnie 150 znaków") String employer,
        @NotNull(message = "Podaj początek") LocalDate startDate,
        @NotNull(message = "Podaj koniec") LocalDate endDate,
        boolean documented) {
}
