package com.intermanage.backend.cash.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CashCancelRequest(
        @NotBlank(message = "Podaj powód anulowania") @Size(max = 300, message = "Najwyżej 300 znaków") String reason) {
}
