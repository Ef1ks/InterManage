package com.intermanage.backend.ai.dto;

import java.time.LocalDate;
import java.util.List;

import com.intermanage.backend.ai.entity.AiFeature;

/** Zużycie tokenów AI w sklepie w okresie [{@code from}, {@code to}] według modelu i funkcji, od największego. */
public record AiUsageReport(LocalDate from, LocalDate to, List<Row> rows, Totals total) {

    public record Row(String model, AiFeature feature, long requests, long promptTokens, long outputTokens,
            long thoughtTokens, long cachedTokens, long totalTokens) {
    }

    public record Totals(long requests, long promptTokens, long outputTokens, long thoughtTokens, long cachedTokens,
            long totalTokens) {
    }
}
