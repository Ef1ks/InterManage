package com.intermanage.backend.sales.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Filtry raportu sprzedaży za okres (parametry zapytania). {@code nomenclatures}: kody rejonów, rodzin albo
 * podrodzin. {@code brands}: marki z kartoteki. {@code suppliers}: kody ostatniego dostawcy artykułu z przyjęć PZ.
 * {@code query}: słowa w nazwie, marce, EAN-ie albo ITM8, wszystkie naraz. {@code parent}: podrodzina rozwijana do
 * artykułów ({@code brak} = bez nomenklatury). {@code depth}: jak głęboko drukować.
 */
public record PeriodSalesRequest(LocalDate from, LocalDate to, List<String> nomenclatures, List<String> brands,
        List<String> suppliers, String query, String parent, PeriodSalesDepth depth) {
}
