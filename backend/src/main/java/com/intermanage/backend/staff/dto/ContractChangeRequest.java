package com.intermanage.backend.staff.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ContractChangeRequest(
        @NotNull(message = "Podaj datę zmiany") LocalDate validFrom,
        @Min(value = 1, message = "Podaj etat") @Max(value = 100, message = "Podaj etat") int fteNumerator,
        @Min(value = 1, message = "Podaj etat") @Max(value = 100, message = "Podaj etat") int fteDenominator) {
}
