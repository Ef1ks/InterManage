package com.intermanage.backend.sales.dto;

import java.util.List;

import com.intermanage.backend.sales.dto.PeriodSummaryResponse.Figures;

/**
 * Rozwinięty wiersz podsumowania sprzedaży i strat: rodziny rejonu, podrodziny rodziny albo artykuły podrodziny
 * (i artykuły bez nomenklatury). Liczby jak w podsumowaniu, bez budżetu; udział liczy przeglądarka ze sprzedaży
 * sklepu, więc tu jest pusty.
 */
public record PeriodSummaryLevelResponse(SalesComparisonResponse.Level level, List<Row> rows) {

    /** Pozycja nomenklatury (kod, nazwa) albo artykuł (ITM8, nazwa, EAN, marka). */
    public record Row(String code, String name, String ean, String brand, Figures figures) {
    }
}
