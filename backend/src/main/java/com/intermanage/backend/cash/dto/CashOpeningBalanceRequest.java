package com.intermanage.backend.cash.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

public record CashOpeningBalanceRequest(
        @NotNull(message = "Podaj datę") LocalDate date,
        @NotNull(message = "Podaj stan kasy")
        @DecimalMin(value = "0.00", message = "Stan kasy nie może być ujemny")
        @Digits(integer = 10, fraction = 2, message = "Kwota z najwyżej dwoma miejscami po przecinku") BigDecimal amount) {
}
