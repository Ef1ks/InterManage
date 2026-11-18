package com.intermanage.backend.sales.component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NavigableMap;
import java.util.SortedMap;

/**
 * Sprzedaż artykułu w dniach, w których był na stanie. Historii stanów nie mamy, więc okresy dostępności odtwarzamy z
 * dostaw: po każdej dostawie towar był od dnia przyjęcia do ostatniej sprzedaży przed kolejną dostawą (albo przed
 * końcem danych). Dostawa, po której nic się nie sprzedało, nie daje okresu, bo nie wiadomo, ile towar leżał. Średnia z
 * całego czasu zaniżałaby sprzedaż artykułu, którego długo nie było.
 */
public final class InStockSales {

    private static final BigDecimal DAYS_IN_WEEK = BigDecimal.valueOf(7);

    private InStockSales() {
    }

    /** {@code days}: dni na stanie, {@code sold}: sprzedana w nich ilość. */
    public record Result(int days, BigDecimal sold) {

        /** Sprzedaż tygodniowa w dniach na stanie. */
        public BigDecimal weekly() {
            return sold.multiply(DAYS_IN_WEEK).divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
        }
    }

    /**
     * @param deliveries dni z dostawą, rosnąco, bez powtórzeń
     * @param sales      sprzedaż dzienna (tylko dni z ilością > 0)
     * @param lastDay    ostatni dzień z wgraną sprzedażą
     * @return okresy na stanie razem albo pusty wynik, gdy po żadnej dostawie nic się nie sprzedało
     */
    public static Result of(List<LocalDate> deliveries, NavigableMap<LocalDate, BigDecimal> sales, LocalDate lastDay) {
        int days = 0;
        BigDecimal sold = BigDecimal.ZERO;
        for (int i = 0; i < deliveries.size(); i++) {
            LocalDate start = deliveries.get(i);
            LocalDate end = i + 1 < deliveries.size() ? deliveries.get(i + 1) : lastDay.plusDays(1);
            if (!start.isBefore(end)) {
                continue;
            }
            SortedMap<LocalDate, BigDecimal> window = sales.subMap(start, end);
            if (window.isEmpty()) {
                continue;
            }
            days += (int) ChronoUnit.DAYS.between(start, window.lastKey()) + 1;
            sold = window.values().stream().reduce(sold, BigDecimal::add);
        }
        return days == 0 ? null : new Result(days, sold);
    }
}
