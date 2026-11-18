package com.intermanage.backend.ai.dto;

/**
 * Zużycie tokenów jednego żądania (wszystkie zapytania do modelu, które w nim padły). {@code model} to model
 * z odpowiedzi; kilka różnych rozdziela przecinek.
 */
public record AiUsageSummary(String model, long requests, long promptTokens, long outputTokens, long thoughtTokens,
        long totalTokens) {
}
