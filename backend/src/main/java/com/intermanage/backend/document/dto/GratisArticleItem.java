package com.intermanage.backend.document.dto;

import java.time.Instant;
import java.util.UUID;

/** Artykuł z listy sklepu, który w przeniesieniach stanów oznacza gratis. */
public record GratisArticleItem(UUID id, String itm8, String ean, String name, String brand, String addedBy,
        Instant addedAt) {
}
