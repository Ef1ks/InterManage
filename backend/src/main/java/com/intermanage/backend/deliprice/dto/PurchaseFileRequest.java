package com.intermanage.backend.deliprice.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Kalkulacje jednego dostawcy do pliku CADBEZP. */
public record PurchaseFileRequest(
        @NotNull @Size(min = 1, max = 2000, message = "Wybierz kalkulacje") List<@NotNull UUID> calculationIds) {
}
