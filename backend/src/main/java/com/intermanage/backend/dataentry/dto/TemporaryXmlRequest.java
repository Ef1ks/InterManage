package com.intermanage.backend.dataentry.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** "Zapisz tymczasowo": tabela do pliku XML i cała tabela z przeglądarki ({@code draft}, JSON) do wczytania. */
public record TemporaryXmlRequest(
        @NotNull @Valid ExportRequest export,
        @Size(max = 20_000_000, message = "Tabela jest za duża do zapisu tymczasowego") String draft) {
}
