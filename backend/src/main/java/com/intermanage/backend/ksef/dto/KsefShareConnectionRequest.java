package com.intermanage.backend.ksef.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/** Sklepy, które dostają połączenie z KSeF tego sklepu (ta sama firma, bez wgrywania certyfikatu). */
public record KsefShareConnectionRequest(
        @NotEmpty(message = "Wybierz co najmniej jeden sklep") List<@NotNull UUID> storeIds) {
}
