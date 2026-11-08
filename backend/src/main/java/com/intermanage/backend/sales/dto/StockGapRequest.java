package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Progi raportu braków towaru (parametry zapytania). {@code minWeekly}: najmniejsza ilość w typowym tygodniu.
 * {@code maxStock}: najwyższy stan, przy którym artykuł bez sprzedaży jest brakiem. {@code noDeliveryDays}: ile dni bez
 * dostawy musi minąć, żeby brak trafił do raportu (0 = bez tego warunku). {@code nomenclatures}: kody rejonów, rodzin
 * albo podrodzin. Puste pola biorą wartości domyślne.
 */
public record StockGapRequest(BigDecimal minWeekly, BigDecimal maxStock, Integer noDeliveryDays,
        List<String> nomenclatures) {
}
