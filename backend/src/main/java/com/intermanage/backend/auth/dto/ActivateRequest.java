package com.intermanage.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record ActivateRequest(@NotBlank(message = "Brak tokenu aktywacyjnego") String token) {
}
