package com.intermanage.backend.deliprice.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/** Cofnięcie importu: kalkulacje z {@link CalculationImportResponse#ids()}. */
public record CalculationImportUndoRequest(@NotEmpty List<@NotNull UUID> ids) {
}
