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

    /** Wiersze z dni przed {@code day}; ta sama paczka, gdy wszystkie są wcześniejsze. */
    public SalesReportBatch before(LocalDate day) {
        int kept = 0;
        for (LocalDate date : dates) {
            if (date.isBefore(day)) {
                kept++;
            }
        }
        if (kept == dates.length) {
            return this;
        }
        LocalDate[] keptDates = new LocalDate[kept];
        String[] keptItm8s = new String[kept];
        String[] keptEans = new String[kept];
        BigDecimal[] keptQuantities = new BigDecimal[kept];
        BigDecimal[] keptCostValues = new BigDecimal[kept];
        BigDecimal[] keptSaleValuesGross = new BigDecimal[kept];
        BigDecimal[] keptVatValues = new BigDecimal[kept];
        BigDecimal[] keptMarginValues = new BigDecimal[kept];
        BigDecimal[] keptMarginPercents = new BigDecimal[kept];
        int target = 0;
        for (int i = 0; i < dates.length; i++) {
            if (dates[i].isBefore(day)) {
                keptDates[target] = dates[i];
                keptItm8s[target] = itm8s[i];
                keptEans[target] = eans[i];
                keptQuantities[target] = quantities[i];
                keptCostValues[target] = costValues[i];
                keptSaleValuesGross[target] = saleValuesGross[i];
                keptVatValues[target] = vatValues[i];
                keptMarginValues[target] = marginValues[i];
                keptMarginPercents[target] = marginPercents[i];
                target++;
            }
        }
        return new SalesReportBatch(keptDates, keptItm8s, keptEans, keptQuantities, keptCostValues,
                keptSaleValuesGross, keptVatValues, keptMarginValues, keptMarginPercents);
    }
}
