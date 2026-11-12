package com.intermanage.backend.pricecheck.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Weryfikacje do cofnięcia albo przywrócenia (toast „Cofnij” / „Przywróć”). */
public record ArticleMarginVerificationIdsRequest(
        @NotEmpty @Size(max = 5000) List<@NotNull UUID> ids) {
}
