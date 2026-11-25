package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Parametry raportu brakującego asortymentu. {@code months}: z ilu ostatnich miesięcy brać przyjęcia PZ (1-12,
 * domyślnie 3). {@code silentDays}: ile dni bez sprzedaży (do ostatniego dnia z wgraną sprzedażą) oznacza, że artykuł
 * się nie sprzedaje (domyślnie 7). {@code maxStock}: zamiast braków artykuły ze stanem najwyżej takim, bez względu na
 * sprzedaż ({@code silentDays} się wtedy nie liczy), np. do zamówienia tego, co się kończy. {@code nomenclatures}: kody rejonów, rodzin albo podrodzin, puste = wszystkie.
 */
public record MissingAssortmentRequest(Integer months, Integer silentDays, BigDecimal maxStock,
        List<String> nomenclatures) {
}
