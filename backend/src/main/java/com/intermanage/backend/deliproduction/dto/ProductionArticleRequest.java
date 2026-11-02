package com.intermanage.backend.deliproduction.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Artykuł bazy kart produkcji. Kod pusty = artykuł wybierany tylko po nazwie. */
public record ProductionArticleRequest(
        @Size(max = 20, message = "Najwyżej 20 znaków")
        @Pattern(regexp = "^\s*[0-9A-Za-z]*\s*$", message = "Kod to cyfry albo litery bez spacji") String code,
        @NotBlank(message = "Wpisz nazwę") @Size(max = 200, message = "Najwyżej 200 znaków") String name,
        @Size(max = 20, message = "Najwyżej 20 znaków") String nomenclature,
        @NotNull(message = "Wybierz schemat") UUID schemaId) {
}
