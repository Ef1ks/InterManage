package com.intermanage.backend.deliprice.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Wyroby z nomenklatury 36 zaznaczone w automatycznym imporcie kalkulacji. */
public record CalculationImportRequest(
        @NotEmpty(message = "Zaznacz co najmniej jeden wyrób")
        @Size(max = 2000, message = "Najwyżej 2000 wyrobów naraz") List<@NotNull UUID> productIds) {
}
