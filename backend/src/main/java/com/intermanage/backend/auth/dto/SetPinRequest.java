package com.intermanage.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SetPinRequest(
        @NotBlank(message = "Podaj obecne hasło") String currentPassword,
        @NotBlank(message = "Podaj PIN") @Pattern(regexp = "\\d{4,8}", message = "PIN to od 4 do 8 cyfr") String pin) {
}
