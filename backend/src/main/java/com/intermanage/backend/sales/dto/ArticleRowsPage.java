package com.intermanage.backend.sales.dto;

import java.util.List;

/**
 * Strona listy w karcie artykułu (dostawy, protokoły): wiersze od podanego miejsca i {@code total} = ile wierszy pasuje
 * do wyszukiwania na wszystkich stronach.
 */
public record ArticleRowsPage<T>(List<T> items, long total) {
}
