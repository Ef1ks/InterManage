package com.intermanage.backend.document.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Sklep zaprzyjaźniony, dla którego wystawiono ruch międzymagazynowy. */
public record SetTransferTargetRequest(@NotNull(message = "Wybierz sklep") UUID storeId) {
}
