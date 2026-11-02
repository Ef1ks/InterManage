package com.intermanage.backend.promotion.dto;

import java.util.List;

/** Ile promocji sklepu trwa albo się zacznie ({@code activeCount}: koniec od dziś) i ostatnie wgrania raportu. */
public record PromotionImportOverview(long activeCount, List<PromotionImportResponse> imports) {
}
