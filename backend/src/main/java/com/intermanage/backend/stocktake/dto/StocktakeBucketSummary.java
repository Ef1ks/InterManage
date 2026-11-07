package com.intermanage.backend.stocktake.dto;

import java.math.BigDecimal;

/** Ile artykułów i jaka kwota bez znaku są w danej grupie różnic. */
public record StocktakeBucketSummary(StocktakeBucket bucket, int lineCount, BigDecimal absoluteValue) {
}
