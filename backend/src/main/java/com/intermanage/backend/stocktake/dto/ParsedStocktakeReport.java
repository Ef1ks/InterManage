package com.intermanage.backend.stocktake.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Odczytany raport. {@code wholeStore}: ramka wyboru obejmuje wszystkie artykuły, więc artykuł, którego w raporcie nie
 * ma, nie ma już różnicy. Wydruk działu tego nie mówi, bo nie wiadomo, co obejmował.
 */
public record ParsedStocktakeReport(
        String storeNumber,
        LocalDateTime generatedAt,
        String stocktakeName,
        String stocktakeNumber,
        LocalDate stocktakeDate,
        String selection,
        boolean wholeStore,
        int pageCount,
        BigDecimal differenceValue,
        List<StocktakeReportRow> rows) {
}
