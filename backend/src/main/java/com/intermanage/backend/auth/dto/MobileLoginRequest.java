package com.intermanage.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record MobileLoginRequest(
        @NotBlank(message = "Podaj login") String username,
        @NotBlank(message = "Podaj hasło") String password) {
}
