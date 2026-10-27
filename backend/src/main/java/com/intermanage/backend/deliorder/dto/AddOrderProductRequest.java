package com.intermanage.backend.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record AddOrderProductRequest(@NotNull(message = "Wybierz produkt") UUID productId) {
}
