package com.intermanage.backend.dataentry.dto;

import java.util.List;

/** Strona listy towarów; {@code hasMore} = są kolejne artykuły pasujące do filtrów. */
public record CatalogListPage(List<CatalogListItem> items, boolean hasMore) {
}
