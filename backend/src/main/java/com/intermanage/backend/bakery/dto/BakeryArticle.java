package com.intermanage.backend.bakery.dto;

import java.util.UUID;

/** Artykuł z kartoteki w tabeli WZ. */
public record BakeryArticle(UUID productId, String itm8, String ean, String name, String brand) {
}
