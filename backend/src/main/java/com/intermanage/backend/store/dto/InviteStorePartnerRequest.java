package com.intermanage.backend.store.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Sklep zapraszany do grona zaprzyjaźnionych. */
public record InviteStorePartnerRequest(@NotNull(message = "Wybierz sklep") UUID storeId) {
}
