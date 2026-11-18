package com.intermanage.backend.sales.dto;

import java.util.List;

/**
 * Parametry raportu brakującego asortymentu. {@code months}: z ilu ostatnich miesięcy brać przyjęcia PZ (1-12,
 * domyślnie 3). {@code silentDays}: ile dni bez sprzedaży (do ostatniego dnia z wgraną sprzedażą) oznacza, że artykuł
 * się nie sprzedaje (domyślnie 7). {@code nomenclatures}: kody rejonów, rodzin albo podrodzin, puste = wszystkie.
 */
public record MissingAssortmentRequest(Integer months, Integer silentDays, List<String> nomenclatures) {
}
