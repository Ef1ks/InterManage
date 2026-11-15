package com.intermanage.backend.product.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Artykuł {@code itm8} w {@code storeId} to ten sam towar co {@code linkedItm8} w {@code linkedStoreId}. */
public record ArticleLinkRequest(
        @NotNull UUID storeId,
        @NotBlank @Pattern(regexp = "\\d{1,13}", message = "ITM8 to do 13 cyfr") String itm8,
        @NotNull UUID linkedStoreId,
        @NotBlank @Pattern(regexp = "\\d{1,13}", message = "ITM8 to do 13 cyfr") String linkedItm8) {
}
