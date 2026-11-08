package com.intermanage.backend.staff.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PositionRequest(
        @NotNull(message = "Wybierz dział") UUID departmentId,
        @NotBlank(message = "Podaj nazwę stanowiska")
        @Size(max = 80, message = "Maksymalnie 80 znaków") String name,
        @NotNull(message = "Wybierz rolę") UUID roleId,
        @NotNull @Size(max = 50, message = "Maksymalnie 50 godzin pracy") List<@Valid ShiftRequest> shifts) {
}
