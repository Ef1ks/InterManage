package com.intermanage.backend.stocktake.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Inwentura po analizie. {@code lines} nie ma grupy {@link StocktakeBucket#REST} (drobne różnice bez powodu, tysiące
 * wierszy), jest tylko jej suma w {@code buckets}. {@code firstAbsoluteValue} to kwota bez znaku z pierwszych wydruków,
 * więc razem z {@code absoluteValue} pokazuje, co dały przeliczenia. {@code salesThrough} to ostatni dzień sprzedaży
 * wzięty pod uwagę (brak = po inwenturze nie ma jeszcze wgranej sprzedaży).
 */
public record StocktakeResponse(
        UUID id,
        String number,
        String name,
        LocalDate stocktakeDate,
        LocalDate salesThrough,
        int lineCount,
        int resolvedCount,
        BigDecimal netValue,
        BigDecimal absoluteValue,
        BigDecimal firstAbsoluteValue,
        List<StocktakeBucketSummary> buckets,
        List<StocktakeLineResponse> lines,
        List<StocktakePairResponse> pairs,
        List<StocktakeImportResponse> imports) {
}
