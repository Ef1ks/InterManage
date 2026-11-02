package com.intermanage.backend.dataentry.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Plik CADBEZP, który program sklepu poprawnie wczytał: jego nowe artykuły zostają szkicami artykułów. */
public record ProductDraftRequest(
        @NotBlank @Size(max = 255) String fileName,
        @NotNull @Valid ProductFileRequest file) {
}
