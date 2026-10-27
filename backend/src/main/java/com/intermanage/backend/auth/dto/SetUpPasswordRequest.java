package com.intermanage.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SetUpPasswordRequest(
        @NotBlank(message = "Podaj hasło")
        @Size(min = 8, max = 72, message = "Hasło musi mieć od 8 do 72 znaków") String newPassword) {
}
