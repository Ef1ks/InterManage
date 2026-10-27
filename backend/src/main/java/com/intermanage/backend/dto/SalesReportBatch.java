package com.intermanage.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Paczka wierszy raportu sprzedaży ułożona kolumnami: {@code itm8s[i]}, {@code dates[i]}... to jeden wiersz.
 * Kolumny idą do bazy jako tablice ({@code unnest}), jednym zapytaniem na paczkę.
 */
public record SalesReportBatch(
        LocalDate[] dates,
        String[] itm8s,
        String[] eans,
        BigDecimal[] quantities,
        BigDecimal[] costValues,
        BigDecimal[] saleValuesGross,
        BigDecimal[] vatValues,
        BigDecimal[] marginValues,
        BigDecimal[] marginPercents) {

    public int size() {
        return dates.length;
    }
}
