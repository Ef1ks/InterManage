package com.intermanage.backend.staff.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Przeniesienie do innego sklepu firmy; stanowiska są wspólne, więc {@code positionId} może zostać to samo. */
public record TransferEmployeeRequest(
        @NotNull(message = "Wybierz sklep") UUID storeId,
        @NotNull(message = "Wybierz stanowisko") UUID positionId,
        @NotNull @Size(max = 50, message = "Maksymalnie 50 godzin pracy") List<@Valid ShiftRequest> shifts) {
}
