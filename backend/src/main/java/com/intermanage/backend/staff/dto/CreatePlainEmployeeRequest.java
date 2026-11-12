package com.intermanage.backend.staff.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Pracownik bez konta w aplikacji: samo imię i nazwisko, bez loginu i e-maila. */
public record CreatePlainEmployeeRequest(
        @NotBlank(message = "Podaj imię i nazwisko")
        @Size(max = 100, message = "Maksymalnie 100 znaków") String displayName,
        @NotNull(message = "Wybierz stanowisko") UUID positionId,
        @NotNull @Size(max = 50, message = "Maksymalnie 50 godzin pracy") List<@Valid ShiftRequest> shifts) {
}
