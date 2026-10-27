package com.intermanage.backend.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Dostawca, od którego jest gratis (z listy dostawców sklepu). */
public record RegisterGratisRequest(@NotNull(message = "Wybierz dostawcę") UUID supplierId) {
}
