package com.intermanage.backend.auth.dto;

import jakarta.validation.constraints.NotNull;

public record PinRequiredRequest(@NotNull(message = "Podaj, czy wymagać PIN-u") Boolean required) {
}
