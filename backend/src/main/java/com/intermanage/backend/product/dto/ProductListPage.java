package com.intermanage.backend.product.dto;

import java.util.List;

/** Strona listy artykułów; {@code total} = wszystkie pasujące do wyszukiwania. */
public record ProductListPage(List<ProductListItem> items, long total, boolean hasMore) {
}
