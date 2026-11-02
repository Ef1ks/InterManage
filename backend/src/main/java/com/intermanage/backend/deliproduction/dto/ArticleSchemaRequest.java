package com.intermanage.backend.deliproduction.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/** Ten sam schemat dla zaznaczonych artykułów naraz. */
public record ArticleSchemaRequest(
        @NotEmpty(message = "Zaznacz artykuły") List<@NotNull UUID> articleIds,
        @NotNull(message = "Wybierz schemat") UUID schemaId) {
}
