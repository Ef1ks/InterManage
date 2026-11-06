package com.intermanage.backend.bakery.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Nowa weryfikacja dostawcy; z {@code documentId} od razu z bonem, jego fakturą i kafelkami WZ. */
public record BakeryCheckRequest(@NotNull(message = "Wybierz dostawcę") UUID supplierId, UUID documentId) {
}
