package com.intermanage.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record ActivateRequest(@NotBlank(message = "Brak tokenu aktywacyjnego") String token) {
}
