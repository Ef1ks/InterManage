package com.intermanage.backend.deliorder.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record AddOrderProductRequest(@NotNull(message = "Wybierz produkt") UUID productId) {
}
