package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Raport przyjętych gratisów: kafelek na każdy miesiąc okresu i, przy kilku miesiącach, kafelek podsumowania
 * ({@code summary}, ten sam układ). W kafelku tylko rejony z gratisem; {@code total.saleValueGross} to sprzedaż brutto
 * całego sklepu, nie tylko tych rejonów.
 */
public record GratisReportResponse(
        String storeNumber,
        String storeName,
        YearMonth from,
        YearMonth to,
        LocalDate lastSaleDate,
        LocalDate documentsCoveredUntil,
        List<Tile> months,
        Tile summary) {

    /** Miesiąc albo podsumowanie okresu; {@code documentCount} to liczba dokumentów-gratisów. */
    public record Tile(YearMonth from, YearMonth to, String label, long documentCount, List<Region> regions,
            Figures total) {
    }

    /** Pusty {@code code} = linie bez nomenklatury. */
    public record Region(String code, String name, Figures figures) {
    }

    /**
     * Gratisy za handel / ekspozycję i za pokrycie strat (sprzedaż brutto linii), sprzedaż brutto i udział gratisów
     * w sprzedaży w % (pusty bez sprzedaży).
     */
    public record Figures(BigDecimal tradeValue, BigDecimal lossValue, BigDecimal gratisValue,
            BigDecimal saleValueGross, BigDecimal gratisPercent) {
    }
}
