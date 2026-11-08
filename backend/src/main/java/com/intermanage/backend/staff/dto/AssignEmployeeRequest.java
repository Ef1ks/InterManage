package com.intermanage.backend.staff.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Pracownik, który ma już konto w aplikacji. */
public record AssignEmployeeRequest(
        @NotNull(message = "Wybierz konto") UUID userId,
        @NotNull(message = "Wybierz stanowisko") UUID positionId,
        @NotNull @Size(max = 50, message = "Maksymalnie 50 godzin pracy") List<@Valid ShiftRequest> shifts) {
}
