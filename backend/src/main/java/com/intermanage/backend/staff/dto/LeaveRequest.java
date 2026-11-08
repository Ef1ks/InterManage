package com.intermanage.backend.staff.dto;

import java.time.LocalDate;

import com.intermanage.backend.staff.entity.LeaveKind;

import jakarta.validation.constraints.NotNull;

/** Dni urlopu liczy serwer z dni pracy w zakresie (art. 154² KP), więc żądanie ich nie podaje. */
public record LeaveRequest(
        @NotNull(message = "Wybierz rodzaj") LeaveKind kind,
        @NotNull(message = "Podaj początek") LocalDate startDate,
        @NotNull(message = "Podaj koniec") LocalDate endDate) {
}
