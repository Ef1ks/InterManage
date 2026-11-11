package com.intermanage.backend.auth.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record BackgroundVeilRequest(
        @NotNull(message = "Podaj przesłonięcie zdjęcia")
        @Min(value = 0, message = "Przesłonięcie od 0 do 95%")
        @Max(value = 95, message = "Przesłonięcie od 0 do 95%") Integer veilPercent) {
}
