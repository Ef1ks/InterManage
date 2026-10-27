package com.intermanage.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "Podaj login") String username,
        @NotBlank(message = "Podaj hasło") String password) {
}
