package com.intermanage.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "Podaj login") String username,
        @NotBlank(message = "Podaj hasło") String password,
        /** „Nie wylogowuj mnie”: trwałe ciasteczko zamiast sesyjnego, brak = nie. */
        Boolean rememberMe) {
}
