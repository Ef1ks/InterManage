package com.intermanage.backend.bakery.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Pełna lista odznaczonych artykułów dostawcy; zastępuje poprzednią. */
public record BakeryHiddenArticlesRequest(@NotNull List<@NotNull UUID> hiddenProductIds) {
}
