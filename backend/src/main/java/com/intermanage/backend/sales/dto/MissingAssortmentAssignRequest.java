package com.intermanage.backend.sales.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Przypisanie artykułów sklepu do kategorii: każdy artykuł ma swoją kategorię docelową, więc „Cofnij” odsyła każdy tam,
 * gdzie był. Pusta {@code categoryId} = bez kategorii (artykuł wraca do „Do sprawdzenia”).
 */
public record MissingAssortmentAssignRequest(@NotEmpty @Size(max = 5000) List<@Valid Item> items) {

    public record Item(@NotBlank String itm8, UUID categoryId) {
    }
}
