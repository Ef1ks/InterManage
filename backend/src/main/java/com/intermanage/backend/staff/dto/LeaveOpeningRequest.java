package com.intermanage.backend.staff.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record LeaveOpeningRequest(
        @Min(value = 2000, message = "Podaj rok") @Max(value = 2100, message = "Podaj rok") int year,
        @Min(value = 0, message = "Podaj liczbę dni") @Max(value = 400, message = "Za dużo dni") int days) {
}
