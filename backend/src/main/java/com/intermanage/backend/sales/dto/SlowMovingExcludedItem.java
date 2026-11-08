package com.intermanage.backend.sales.dto;

import java.time.Instant;
import java.util.UUID;

/** Artykuł z listy pomijanych w raporcie niesprzedających się. */
public record SlowMovingExcludedItem(UUID id, String itm8, String ean, String name, String brand, String excludedBy,
        Instant excludedAt) {
}
