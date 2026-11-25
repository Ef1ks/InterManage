package com.intermanage.backend.cadtop.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Artykuły z listy: karta sklepu i ITM8 z cadencier (proponowany albo jeden z pozostałych z tym EAN-em). */
public record CadPriorityRequest(
        @NotEmpty(message = "Zaznacz artykuły") @Size(max = 1000) List<@Valid @NotNull Item> items) {

    public record Item(@NotBlank String itm8, @NotBlank String topItm8) {
    }

    /** Zlecenie na komputer sklepu ({@code agentId} puste = wybiera serwer). */
    public record Remote(@NotEmpty(message = "Zaznacz artykuły") @Size(max = 1000) List<@Valid @NotNull Item> items,
            UUID agentId) {

        public CadPriorityRequest chosen() {
            return new CadPriorityRequest(items);
        }
    }

    public record Automatic(@NotNull Boolean enabled) {
    }

    public record Runs(@NotEmpty @Size(max = 1000) List<@NotNull UUID> ids) {
    }
}
