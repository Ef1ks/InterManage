package com.intermanage.backend.product.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Plik cenówek: {@code items} z liczbą cenówek każdego wpisu (starsze {@code ids} = po 1) i opcjonalna własna nazwa
 * w miejscu formatu cenówki ({@code name}, pusta = format rodzaju, np. {@code PROPROFOR67}).
 */
public record PriceLabelsFileRequest(List<UUID> ids, List<@Valid Item> items, @Size(max = 40) String name) {

    public record Item(@NotNull UUID id, @NotNull @Min(1) @Max(9999) Integer count) {
    }

    /** Wszystkie wpisy z liczbą cenówek; {@code ids} bez {@code items} dają po 1. */
    public List<Item> chosen() {
        if (items != null && !items.isEmpty()) {
            return items;
        }
        return ids == null ? List.of() : ids.stream().map(id -> new Item(id, 1)).toList();
    }
}
